(require 'cider)

;; (add-to-list 'cider-jack-in-nrepl-middlewares "flow-storm.nrepl.middleware/wrap-flow-storm")

(nrepl-dict-get (cider-storm-find-first-fn-call "dev-tester/boo") "form-id")
;; (cider-var-info "dev-tester/boo")
(cider-storm-get-form 440181832)
;; (cider-storm-timeline-entry nil 20 7 "next")

(cider-nrepl-send-sync-request `("op" "xxx"))


;;;;;;;;;;;;;;;;;;;;
;; Debugger state ;;
;;;;;;;;;;;;;;;;;;;;

(defvar cider-storm-current-flow-id nil)
(defvar cider-storm-current-thread-id nil)
(defvar cider-storm-current-entry nil)
(defvar cider-storm-current-frame nil)

;;;;;;;;;;;;;;;;;;;;
;; Middleware api ;;
;;;;;;;;;;;;;;;;;;;;

(defun cider-storm-find-first-fn-call (fq-fn-symb)
  (thread-first (cider-nrepl-send-sync-request `("op"         "flow-storm-find-first-fn-call"
												 "fq-fn-symb" ,fq-fn-symb))
				(nrepl-dict-get "fn-call")))

(defun cider-storm-get-form (form-id)
  (thread-first (cider-nrepl-send-sync-request `("op"         "flow-storm-get-form"
												 "form-id" ,form-id))
				(nrepl-dict-get "form")))

(defun cider-storm-timeline-entry (flow-id thread-id idx drift)
  (thread-first (cider-nrepl-send-sync-request `("op"        "flow-storm-timeline-entry"
												 "flow-id"   ,flow-id
												 "thread-id" ,thread-id
												 "idx"       ,idx
												 "drift"     ,drift))
				(nrepl-dict-get "entry")))

(defun cider-storm-frame-data (flow-id thread-id fn-call-idx)
  (thread-first (cider-nrepl-send-sync-request `("op"          "flow-storm-frame-data"
												 "flow-id"     ,flow-id
												 "thread-id"   ,thread-id
												 "fn-call-idx" ,fn-call-idx))
				(nrepl-dict-get "frame")))

(defun cider-storm-pprint-val-ref (v-ref print-length print-level print-meta pprint)
  (thread-first (cider-nrepl-send-sync-request `("op"          "flow-storm-pprint"
												 "val-ref"      ,v-ref
												 "print-length" ,print-length
												 "print-level"  ,print-level
												 "print-meta"   ,(if print-meta "true" "false")
												 "pprint"       ,(if pprint     "true" "false")))
				(nrepl-dict-get "pprint")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Debugger implementation ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defun cider-storm-select-form (form-id)
  (let* ((form (cider-storm-get-form form-id))
		 (form-file (nrepl-dict-get form "form/file"))
		 (form-line (nrepl-dict-get form "form/line")))
	(if (and form-file form-line)
		(when-let* ((buf (cider--find-buffer-for-file form-file)))
		  (with-current-buffer buf
			(forward-line (- form-line (line-number-at-pos)))))        
		(message "Opening forms without file and line not supported yet."))))

(defun cider-storm-entry-type (entry)
  (pcase (nrepl-dict-get entry "type")
	("fn-call"   'fn-call)
	("fn-return" 'fn-return)
	("expr"      'expr)))

(defun cider-storm-display-step (form-id entry)

  (cider-storm-select-form form-id)

  (let* ((entry-type (cider-storm-entry-type entry)))

	(when-let* ((coord (nrepl-dict-get entry "coord")))
	  (cider--debug-move-point coord))

	(when (or (eq entry-type 'fn-return)
			  (eq entry-type 'expr))
	  (let* ((val-ref (nrepl-dict-get entry "result"))
			 (val-pprint (cider-storm-pprint-val-ref val-ref
													50
													3
													nil
													nil))
			 (val-type (nrepl-dict-get val-pprint "val-type"))
			 (val-str (nrepl-dict-get val-pprint "val-str")))

		(cider--debug-remove-overlays)
		(cider--debug-display-result-overlay val-str)))))

(defun cider-storm-show-help ()
  (let* ((help-text "INSERT HELP HERE")
		 (help-buf (cider-popup-buffer "*cider-storm-help*" 'select)))
	(with-current-buffer val-buffer
	  (let ((inhibit-read-only t))			
		(insert help-text)))))

(defun cider-storm-pprint-current-entry ()
  (let* ((entry-type (cider-storm-entry-type cider-storm-current-entry)))
	(when (or (eq entry-type 'fn-return)
			  (eq entry-type 'expr))
	  (let* ((val-ref (nrepl-dict-get cider-storm-current-entry "result"))
			 (val-pprint (cider-storm-pprint-val-ref val-ref
													 50
													 3
													 nil
													 't))
			 (val-type (nrepl-dict-get val-pprint "val-type"))
			 (val-str (nrepl-dict-get val-pprint "val-str"))
			 (val-buffer (cider-popup-buffer "*cider-storm-pprint*" 'select 'clojure-mode)))
		
		(with-current-buffer val-buffer
		  (let ((inhibit-read-only t))			
			(insert val-str)))))))

(defun cider-storm-jump-to-code (flow-id thread-id next-entry)
  (let* ((curr-fn-call-idx (nrepl-dict-get cider-storm-current-frame "fn-call-idx"))
		 (next-fn-call-idx (nrepl-dict-get next-entry "fn-call-idx"))
		 (changing-frame? (not (eq curr-fn-call-idx next-fn-call-idx)))
		 (curr-frame (if changing-frame?
						 (let* ((first-frame (cider-storm-frame-data flow-id thread-id 0))
								(first-entry (cider-storm-timeline-entry flow-id thread-id 0 "at")))
						   (setq cider-storm-current-frame first-frame)
						   (setq cider-storm-current-entry first-entry)
						   first-frame)
					   cider-storm-current-frame))
		 (curr-idx (nrepl-dict-get cider-storm-current-entry "idx"))
		 (next-idx (nrepl-dict-get next-entry "idx"))		 
         
		 (next-frame (if changing-frame?
						 (cider-storm-frame-data flow-id thread-id next-fn-call-idx)
					   cider-storm-current-frame))
		 (curr-form-id (nrepl-dict-get cider-storm-current-frame "form-id"))
		 (next-form-id (nrepl-dict-get next-frame "form-id"))
		 (first-jump? (and (zerop curr-idx) (zerop next-idx)))
		 (changing-form? (not (eq curr-form-id next-form-id))))
		
	(when changing-frame?
	  (setq cider-storm-current-frame next-frame))

	(cider-storm-display-step next-form-id next-entry)
	
	(setq cider-storm-current-entry next-entry)))

(defun cider-storm-step (drift)
  (interactive)
  (let* ((curr-idx (nrepl-dict-get cider-storm-current-entry "idx")))
	(if curr-idx
		(let* ((next-entry (cider-storm-timeline-entry cider-storm-current-flow-id
													  cider-storm-current-thread-id
													  curr-idx
													  drift)))
		  (cider-storm-jump-to-code cider-storm-current-flow-id
								   cider-storm-current-thread-id
								   next-entry))

	  (message "Not pointing at any recording entry."))))

(defun cider-storm-debug-current-fn ()
  (interactive)

  (cider-try-symbol-at-point
   "Debug fn"
   (lambda (var-name)
	 (let* ((info (cider-var-info "dev-tester/boo" ;;var-name
								  ))
			(fn-ns (nrepl-dict-get info "ns"))
			(fn-name (nrepl-dict-get info "name"))
            (fq-fn-symb (format "%s/%s" fn-ns fn-name)))
	   (when (and fn-ns fn-name)
         (let* ((fn-call (cider-storm-find-first-fn-call fq-fn-symb)))
		   (if fn-call
			   (let* ((form-id (nrepl-dict-get fn-call "form-id"))
					  (flow-id (nrepl-dict-get fn-call "flow-id"))
					  (thread-id (nrepl-dict-get fn-call "thread-id")))
				 (setq cider-storm-current-entry fn-call)
				 (setq cider-storm-current-flow-id flow-id)
				 (setq cider-storm-current-thread-id thread-id)
				 (cider-storm-display-step form-id fn-call)
				 (cider-storm-debugging-mode 1))
			 (message "No recording found for %s/%s" fn-ns fn-name))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; cider-storm minor mode ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(define-minor-mode cider-storm-debugging-mode
  "Toggle cider-storm-debugging-mode"
  ;; The initial value.
  :init-value nil
  ;; The indicator for the mode line.
  :lighter " STORM-DBG"
  ;; The minor mode bindings.
  :keymap
  '(("q" . (lambda () (interactive) (cider--debug-remove-overlays) (cider-storm-debugging-mode -1)))
	("n" . (lambda () (interactive) (cider-storm-step "next")))
	("p" . (lambda () (interactive) (cider-storm-step "prev")))
	("h" . (lambda () (interactive) (cider-storm-show-help)))
	("." . (lambda () (interactive) (cider-storm-pprint-current-entry)))))
