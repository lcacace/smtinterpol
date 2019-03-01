(set-option :produce-proofs true)
(set-option :proof-check-mode true)
(set-option :model-check-mode true)
(set-option :print-terms-cse false)

(set-logic QF_LRA)
(declare-const x Real)

(push 1)
(assert (not (= (/ x 3) (* (/ 1.0 3.0) x))))
(check-sat)
(get-proof)
(pop 1)

(push 1)
(assert (not (= (/ x 5 3) (/ x 15))))
(check-sat)
(get-proof)
(pop 1)

(push 1)
(assert (not (= (/ x 3 5) (/ x 15 1))))
(check-sat)
(get-proof)
(pop 1)