/*
 * Copyright (C) 2019 University of Freiburg
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
package de.uni_freiburg.informatik.ultimate.smtinterpol.theory.quant.ematching;

import de.uni_freiburg.informatik.ultimate.smtinterpol.theory.cclosure.CCTerm;

/**
 * A trigger for comparing two CCTerms. It has to be installed into the CClosure. Upon activation, the remaining
 * E-Matching code can be executed with the given register.
 * 
 * @author Tanja Schindler
 */
public class EMCompareTrigger extends de.uni_freiburg.informatik.ultimate.smtinterpol.theory.cclosure.CompareTrigger {

	private final EMatching mEMatching;
	private final ICode mRemainingCode;
	private final CCTerm[] mRegister;

	public EMCompareTrigger(final EMatching eMatching, final ICode remainingCode, final CCTerm[] register) {
		mEMatching = eMatching;
		mRemainingCode = remainingCode;
		mRegister = register;
	}

	@Override
	public void activate() {
		mEMatching.addCode(mRemainingCode, mRegister);
	}

}