(set-option :produce-interpolants true)
(set-option :interpolant-check-mode true)

(set-info :smt-lib-version 2.6)
(set-logic QF_DT)

(set-info :category "crafted")
(set-info :status unsat)


(declare-datatypes ( (List 0) (Nat 0) ) (
 ( (nil) (cons2 (car2 Nat) (cdr2 Nat)) (cons (car Nat) (cdr List) ))
 ( (zero) (succ (pred Nat)) )
))

(declare-const a Nat)
(declare-const u List)
(declare-const v List)
(declare-const w List)

;; tester

(assert (! true :named A ))
(assert (! (not ((_ is cons) (cons a u))) :named B )) 

(check-sat)
(get-interpolants A B)
(get-interpolants B A)
(exit)