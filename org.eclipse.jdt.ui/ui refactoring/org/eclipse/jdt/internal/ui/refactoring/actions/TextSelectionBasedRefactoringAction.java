/*
 * (c) Copyright IBM Corp. 2000, 2001.
 * All Rights Reserved.
 */
package org.eclipse.jdt.internal.ui.refactoring.actions;

import org.eclipse.jface.text.ITextSelection;

import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.JavaModelException;

import org.eclipse.jdt.internal.corext.refactoring.base.Refactoring;
import org.eclipse.jdt.internal.ui.refactoring.RefactoringWizard;
import org.eclipse.jdt.internal.ui.util.ExceptionHandler;

import org.eclipse.jdt.internal.ui.refactoring.actions.TextSelectionAction;

public abstract class TextSelectionBasedRefactoringAction extends TextSelectionAction{

	protected TextSelectionBasedRefactoringAction(String name, String operationNonAvailableDialogTitle, String operationNonAvailableDialogMessage) {
		super(name, operationNonAvailableDialogTitle, operationNonAvailableDialogMessage);
	}
	
	/* (non-JavaDoc)
	 * Method declared in IAction.
	 */
	public void run() {
	try{
			Refactoring refactoring=  createRefactoring(getCompilationUnit(), getTextSelection());
			RefactoringAction.activateRefactoringWizard(refactoring, createWizard(refactoring), getText(), false);
		} catch (JavaModelException e){
			ExceptionHandler.handle(e, getText(), "Unexpected exception occurred. See log for details.");
		}	
	}
	
	abstract Refactoring createRefactoring(ICompilationUnit cunit, ITextSelection selection);
	abstract RefactoringWizard createWizard(Refactoring refactoring);
}

