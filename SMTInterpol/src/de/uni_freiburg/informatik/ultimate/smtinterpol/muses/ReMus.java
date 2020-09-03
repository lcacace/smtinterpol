/*
 * Copyright (C) 2020 Leonard Fichtner
 *
 * This file is part of SMTInterpol.
 *
 * SMTInterpol is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * SMTInterpol is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with SMTInterpol.  If not, see <http://www.gnu.org/licenses/>.
 */
package de.uni_freiburg.informatik.ultimate.smtinterpol.muses;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Random;

import de.uni_freiburg.informatik.ultimate.logic.SMTLIBException;
import de.uni_freiburg.informatik.ultimate.logic.Script.LBool;
import de.uni_freiburg.informatik.ultimate.smtinterpol.util.TimeoutHandler;

/**
 * This is an implementation of the ReMUS algorithm (see Recursive Online Enumeration of all Minimal Unsatisfiable
 * Subsets, Bendik et al.).
 *
 * @author LeonardFichtner
 *
 */
public class ReMus implements Iterator<MusContainer> {

	ConstraintAdministrationSolver mSolver;
	UnexploredMap mMap;
	TimeoutHandler mTimeoutHandler;
	long mTimeout;
	Random mRnd;

	ArrayList<MusContainer> mMuses;
	MusContainer mNextMus;
	boolean mProvisionalSat;
	BitSet mMaxUnexplored;
	ArrayList<Integer> mMcs;
	BitSet mPreviousCrits;
	BitSet mNewImpliedCrits;
	BitSet mWorkingSet;
	ReMus mSubordinateRemus;
	boolean mMembersUpToDate;

	boolean mSatisfiableCaseLoopIsRunning;
	BitSet mSatisfiableCaseLoopNextWorkingSet;
	boolean mTimeoutOrTerminationRequestOccurred;

	/**
	 * The solver and the map MUST have the same Translator. The SMTSolver and the DPLLEngine must also have the given
	 * TimeoutHandler as TerminationRequest. ReMUS assumes ownership over the solver, the map and the handler. In case
	 * you still want to keep the original state of the solver even after enumerating, use {@link #resetSolver()}. If
	 * timeout <= 0, remus does not measure the time itself, but it still listens to
	 * {@link TimeoutHandler#isTerminationRequested()}. If timeout > 0, remus additionaly measures time. In that case
	 * timeout dictates how much time remus has per method (next, hasNext, enumerate) in miliseconds. If the timeout is
	 * exceeded or termination is requested otherwise, remus stops the computation as fast as possible and afterwards,
	 * it can not continue the enumeration anymore. Note that the SMTSolver and the DPLLEngine of solver and map can
	 * have separate timeouts/TerminationRequests, which can affect remus (and they should therefore be set
	 * accordingly).
	 */
	public ReMus(final ConstraintAdministrationSolver solver, final UnexploredMap map, final BitSet workingSet,
			final TimeoutHandler handler, final long timeout, final Random rnd) {
		mSolver = solver;
		mMap = map;
		mTimeoutHandler = handler;
		mTimeout = timeout;
		mTimeoutOrTerminationRequestOccurred = false;
		mRnd = rnd;

		if (workingSet.length() > mSolver.getNumberOfConstraints()) {
			throw new SMTLIBException(
					"There are constraints set in the workingSet that are not registered in the translator of the "
							+ "solver and the map");
		}
		mWorkingSet = workingSet;
		initializeMembersAndAssertImpliedCrits();
	}

	/**
	 * Constructor for internal instances, that are created for recursion.
	 */
	private ReMus(final ConstraintAdministrationSolver solver, final UnexploredMap map, final BitSet workingSet,
			final TimeoutHandler handler, final Random rnd) {
		this(solver, map, workingSet, handler, 0, rnd);
	}

	private void initializeMembersAndAssertImpliedCrits() {
		mMuses = new ArrayList<>();
		mSolver.clearUnknownConstraints();
		mPreviousCrits = mSolver.getCrits();
		mMap.messWithActivityOfAtoms(mRnd);
		mMaxUnexplored = mMap.findMaximalUnexploredSubsetOf(mWorkingSet);
		mNewImpliedCrits = mMap.findImpliedCritsOf(mWorkingSet);
		mNewImpliedCrits.andNot(mPreviousCrits);
		// If maxUnexplored does not contain some of the known crits, it must be satisfiable
		mProvisionalSat = !MusUtils.contains(mMaxUnexplored, mPreviousCrits);
		mMembersUpToDate = true;
	}

	/**
	 * This method might be costly, since it might have to search for a new mus first, before it knows whether another
	 * mus exists or not. This returns false, if a timeout or a request for termination occurred, except when a Mus has
	 * been found before (by calling hasNext()) and has not been "consumed" by next().
	 */
	@Override
	public boolean hasNext() throws SMTLIBException {
		if (mNextMus != null) {
			return true;
		}

		boolean thisMethodHasSetTheTimeout = false;
		if (mTimeoutOrTerminationRequestOccurred) {
			return false;
		}

		if (mTimeout > 0 && !mTimeoutHandler.timeoutIsSet()) {
			mTimeoutHandler.setTimeout(mTimeout);
			thisMethodHasSetTheTimeout = true;
		}

		if (mSubordinateRemus != null && mSubordinateRemus.hasNext()) {
			mNextMus = mSubordinateRemus.next();
			return true;
		} else {
			removeSubordinateRemus();
		}

		if (mSatisfiableCaseLoopIsRunning) {
			resumeSatisfiableCaseLoopUntilNextMus();
		}
		if (mNextMus != null) {
			return true;
		}

		searchForNextMusBeginningInThisRecursionLevel();
		if (mNextMus != null) {
			return true;
		}

		if (mTimeoutHandler.isTerminationRequested()) {
			mTimeoutOrTerminationRequestOccurred = true;
		}
		if (thisMethodHasSetTheTimeout) {
			mTimeoutHandler.clearTimeout();
		}
		return false;
	}

	/**
	 * Resumes the satisfiable case loop in {@link #handleUnexploredIsSat()} until the next mus is found (by a
	 * subordinate remus) or the loop is finished.
	 */
	private void resumeSatisfiableCaseLoopUntilNextMus() {
		if (mSubordinateRemus != null) {
			throw new SMTLIBException("Let the subordinate find it's muses first.");
		}
		int critical;
		while (!mMcs.isEmpty() && mNextMus == null && !mTimeoutHandler.isTerminationRequested()) {
			critical = MusUtils.pop(mMcs);
			mSatisfiableCaseLoopNextWorkingSet.set(critical);
			createNewSubordinateRemusWithExtraCrit(mSatisfiableCaseLoopNextWorkingSet, critical);
			if (mSubordinateRemus.hasNext()) {
				mNextMus = mSubordinateRemus.next();
			} else {
				removeSubordinateRemus();
			}
			mSatisfiableCaseLoopNextWorkingSet.clear(critical);
		}
		mSatisfiableCaseLoopIsRunning = !mMcs.isEmpty() && !mTimeoutHandler.isTerminationRequested() ? true : false;
	}

	/**
	 * This represents the usual ReMUS loop. It searches for the next mus, beginning in this recursion level, until the
	 * next mus is found, or the whole search space is explored. Before calling this method, first the subordinateRemus
	 * must be exhausted (in terms of the muses it can deliver) and the satisfiable case loop in
	 * {@link #handleUnexploredIsSat()} that has maybe been paused must be resumed and finished. After executing this
	 * method and a mus has been found, this class has a new subordinate remus and maybe it paused the satisfiable case
	 * loop.
	 */
	private void searchForNextMusBeginningInThisRecursionLevel() {
		if (mSubordinateRemus != null) {
			throw new SMTLIBException("Let the subordinate find it's muses first.");
		}
		if (mSatisfiableCaseLoopIsRunning) {
			throw new SMTLIBException("Finish the Satisfiable case loop first.");
		}

		if (!mMembersUpToDate) {
			updateMembersAndAssertImpliedCrits();
		}
		while (!mMaxUnexplored.isEmpty() && mNextMus == null && !mTimeoutHandler.isTerminationRequested()) {
			assert mMembersUpToDate && mSubordinateRemus == null : "Variables of ReMus are corrupted.";
			if (mProvisionalSat) {
				handleUnexploredIsSat();
			} else {
				final BitSet unknowns = (BitSet) mMaxUnexplored.clone();
				mSolver.assertUnknownConstraints(unknowns);
				final LBool sat = mSolver.checkSat();
				mSolver.clearUnknownConstraints();
				if (sat == LBool.SAT) {
					handleUnexploredIsSat();
				} else if (sat == LBool.UNSAT) {
					handleUnexploredIsUnsat();
				} else {
					if (mTimeoutHandler.isTerminationRequested()) {
						return;
					}
					throw new SMTLIBException("CheckSat returns UNKNOWN in Mus enumeration process.");
				}
			}
			// Don't updateMembers while another ReMus is in work, since in the update also crits are asserted
			// which will be removed (because of popRecLevel) after the SubordinateRemus is finished.
			if (mSubordinateRemus == null && !mTimeoutHandler.isTerminationRequested()) {
				updateMembersAndAssertImpliedCrits();
			} else {
				mMembersUpToDate = false;
			}
		}
	}

	private void updateMembersAndAssertImpliedCrits() {
		mNewImpliedCrits.or(mPreviousCrits);
		mPreviousCrits = mNewImpliedCrits;
		mMap.messWithActivityOfAtoms(mRnd);
		mMaxUnexplored = mMap.findMaximalUnexploredSubsetOf(mWorkingSet);
		mNewImpliedCrits = mMap.findImpliedCritsOf(mWorkingSet);
		mNewImpliedCrits.andNot(mPreviousCrits);
		mSolver.assertCriticalConstraints(mNewImpliedCrits);
		// If maxUnexplored does not contain some of the known crits, it must be satisfiable
		mProvisionalSat = !MusUtils.contains(mMaxUnexplored, mPreviousCrits);
		mMembersUpToDate = true;
	}

	private void handleUnexploredIsSat() {
		// To get an extension here is useless, since maxUnexplored is a MSS already. Also BlockUp is useless, since we
		// will block those sets anyways in the following recursion or have already blocked those sets
		mMap.BlockDown(mMaxUnexplored);
		final BitSet bitSetMcs = (BitSet) mWorkingSet.clone();
		bitSetMcs.andNot(mMaxUnexplored);
		if (bitSetMcs.cardinality() == 1) {
			mSolver.assertCriticalConstraint(bitSetMcs.nextSetBit(0));
		} else {
			mMcs = MusUtils.randomPermutation(bitSetMcs, mRnd);
			final BitSet nextWorkingSet = (BitSet) mMaxUnexplored.clone();

			int critical;
			while (!mMcs.isEmpty() && mNextMus == null && !mTimeoutHandler.isTerminationRequested()) {
				critical = MusUtils.pop(mMcs);
				nextWorkingSet.set(critical);
				createNewSubordinateRemusWithExtraCrit(nextWorkingSet, critical);
				if (mSubordinateRemus.hasNext()) {
					mNextMus = mSubordinateRemus.next();
				} else {
					removeSubordinateRemus();
				}
				nextWorkingSet.clear(critical);
				mSatisfiableCaseLoopNextWorkingSet = nextWorkingSet;
			}
			mSatisfiableCaseLoopIsRunning = !mMcs.isEmpty() ? true : false;
		}
	}

	private void handleUnexploredIsUnsat() {
		mSolver.pushRecLevel();
		mNextMus = Shrinking.shrink(mSolver, mMaxUnexplored, mMap, mTimeoutHandler, mRnd);
		mSolver.popRecLevel();
		if (mNextMus == null) {
			return;
		}
		final BitSet nextWorkingSet = (BitSet) mNextMus.getMus().clone();
		// The somewhat arbitrary number 0.9 is a heuristic from the original paper
		if (nextWorkingSet.cardinality() < 0.9 * mMaxUnexplored.cardinality()) {
			for (int i = mMaxUnexplored.nextSetBit(0); nextWorkingSet.cardinality() < 0.9
					* mMaxUnexplored.cardinality(); i = mMaxUnexplored.nextSetBit(i + 1)) {
				if (!nextWorkingSet.get(i)) {
					nextWorkingSet.set(i);
				}
			}
			createNewSubordinateRemus(nextWorkingSet);
		}
	}

	private void createNewSubordinateRemus(final BitSet nextWorkingSet) {
		mSolver.pushRecLevel();
		mSubordinateRemus = new ReMus(mSolver, mMap, nextWorkingSet, mTimeoutHandler, mRnd);
	}

	/**
	 * Creates a new subordinate remus, but a critical constraint is asserted on the level of the subordinate remus (and
	 * this critical constraint will be popped again after the subordinate remus has finished enumerating).
	 */
	private void createNewSubordinateRemusWithExtraCrit(final BitSet nextWorkingSet, final int crit) {
		mSolver.pushRecLevel();
		mSolver.assertCriticalConstraint(crit);
		mSubordinateRemus = new ReMus(mSolver, mMap, nextWorkingSet, mTimeoutHandler, mRnd);
	}

	private void removeSubordinateRemus() {
		if (mSubordinateRemus != null) {
			mSubordinateRemus = null;
			mSolver.popRecLevel();
		}
	}

	@Override
	public MusContainer next() throws SMTLIBException {
		if (hasNext()) {
			final MusContainer nextMus = mNextMus;
			mNextMus = null;
			return nextMus;
		} else {
			throw new NoSuchElementException();
		}
	}

	/**
	 * Finds and returns the rest of the muses. In case of a timeout or a request for termination, this returns the
	 * muses that have been found so far.
	 */
	public ArrayList<MusContainer> enumerate() throws SMTLIBException {
		boolean thisMethodHasSetTheTimeout = false;
		if (mTimeout > 0) {
			mTimeoutHandler.setTimeout(mTimeout);
			thisMethodHasSetTheTimeout = true;
		}
		final ArrayList<MusContainer> restOfMuses = new ArrayList<>();
		while (hasNext()) {
			restOfMuses.add(next());
		}
		if (thisMethodHasSetTheTimeout) {
			mTimeoutHandler.clearTimeout();
		}
		return restOfMuses;
	}

	/**
	 * Resets the internal SMTSolver, such that it will be in the state it was before the instantiation of the
	 * ConstraintAdministrationSolver. But this means, that this iterator can no longer be used.
	 */
	public void resetSolver() {
		mTimeoutOrTerminationRequestOccurred = true;
		mSolver.reset();
	}
}
