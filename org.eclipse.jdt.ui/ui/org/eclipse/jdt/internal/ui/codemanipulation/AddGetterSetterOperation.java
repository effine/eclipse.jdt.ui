/*
 * (c) Copyright IBM Corp. 2000, 2001.
 * All Rights Reserved.
 */
package org.eclipse.jdt.internal.ui.codemanipulation;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IWorkspaceRunnable;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.SubProgressMonitor;

import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaConventions;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.Signature;

import org.eclipse.jdt.internal.ui.preferences.CodeGenerationPreferencePage;
import org.eclipse.jdt.internal.ui.util.JavaModelUtil;

/**
 * For given fields, method stubs for getters and setters are created.
 */
public class AddGetterSetterOperation implements IWorkspaceRunnable {

	private IField[] fFields;
	private List fCreatedAccessors;
	
	private IRequestQuery fSkipExistingQuery;
	private IRequestQuery fSkipFinalSettersQuery;
	
	private boolean fSkipAllFinalSetters;
	private boolean fSkipAllExisting;
	
	private String[] fNamePrefixes;
	private String[] fNameSuffixes;
	
	
	/**
	 * Creates the operation.
	 * @param fields The fields to create setter/getters for.
	 * @param skipFinalSettersQuery Callback to ask if the setter can be skipped for a final field.
	 *        Argument of the query is the final field. <code>null</code> is a valid input and stands for skip all.
	 * @param skipExistingQuery Callback to ask if setter / getters that already exist can be skipped.
	 *        Argument of the query is the existing method. <code>null</code> is a valid input and stands for skip all.
	 */
	public AddGetterSetterOperation(IField[] fields, String[] prefixes, String[] suffixes, IRequestQuery skipFinalSettersQuery, IRequestQuery skipExistingQuery) {
		super();
		fFields= fields;
		fSkipExistingQuery= skipExistingQuery;
		fSkipFinalSettersQuery= skipFinalSettersQuery;
		fNamePrefixes= prefixes;
		fNameSuffixes= suffixes;
		
		fCreatedAccessors= new ArrayList();
	}
	
	/**
	 * The policy to evaluate the base name (no 'set'/'get' of the accessor.
	 */
	private String evalAccessorName(String fieldname) {
		String name= fieldname;
		int bestLength= 0;
		if (fNamePrefixes != null) {
			for (int i= 0; i < fNamePrefixes.length; i++) {
				String curr= fNamePrefixes[i];
				if (fieldname.startsWith(curr)) {
					int currLen= curr.length();
					if (bestLength < currLen && fieldname.length() != currLen) {
						String cand= fieldname.substring(currLen);
						if (JavaConventions.validateFieldName(cand).isOK()) {
							bestLength= currLen;
							name= cand;
						}
					}
				}
			}
		}
		if (fNameSuffixes != null) {
			for (int i= 0; i < fNameSuffixes.length; i++) {
				String curr= fNameSuffixes[i];
				if (fieldname.endsWith(curr)) {
					int currLen= curr.length();
					if (bestLength < currLen && fieldname.length() != currLen) {
						String cand= fieldname.substring(0, fieldname.length() - currLen);
						if (JavaConventions.validateFieldName(cand).isOK()) {
							bestLength= currLen;
							name= cand;
						}
					}
				}
			}
		}			
		return name;
	}
	
	/**
	 * Creates the name of the parameter from an accessor name.
	 */	
	private String getArgumentName(String accessorName) {
		if (accessorName.length() > 0) {
			char firstLetter= accessorName.charAt(0);
			if (Character.isUpperCase(firstLetter)) {
				accessorName= String.valueOf(Character.toLowerCase(firstLetter)) + accessorName.substring(1);
				if (!JavaConventions.validateFieldName(accessorName).isOK()) {
					accessorName= "arg";
				}
			}
		}
		return accessorName;
	}
	
	/**
	 * Runs the operation.
	 * @throws OperationCanceledException Runtime error thrown when operation is cancelled.
	 */
	public void run(IProgressMonitor monitor) throws CoreException, OperationCanceledException {
		if (monitor == null) {
			monitor= new NullProgressMonitor();
		}
		try {			
			int nFields= fFields.length;			
			monitor.beginTask(CodeManipulationMessages.getString("AddGetterSetterOperation.description"), nFields); //$NON-NLS-1$
			
			for (int i= 0; i < nFields; i++) {
				generateStubs(fFields[i], new SubProgressMonitor(monitor, 1));
				if (monitor.isCanceled()) {
					throw new OperationCanceledException();
				}
			}
		} finally {
			monitor.done();
		}
	}
	
	private boolean querySkipFinalSetters(IField field) throws OperationCanceledException {
		if (!fSkipAllFinalSetters) {
			switch (fSkipFinalSettersQuery.doQuery(field)) {
				case IRequestQuery.CANCEL:
					throw new OperationCanceledException();
				case IRequestQuery.NO:
					return false;
				case IRequestQuery.YES_ALL:
					fSkipAllFinalSetters= true;
			}
		}
		return true;
	}
	
	private boolean querySkipExistingMethods(IMethod method) throws OperationCanceledException {
		if (!fSkipAllExisting) {
			switch (fSkipExistingQuery.doQuery(method)) {
				case IRequestQuery.CANCEL:
					throw new OperationCanceledException();
				case IRequestQuery.NO:
					return false;
				case IRequestQuery.YES_ALL:
					fSkipAllExisting= true;
			}
		}
		return true;
	}	
	
	/**
	 * Creates setter and getter for a given field.
	 */
	private void generateStubs(IField field, IProgressMonitor monitor) throws JavaModelException, OperationCanceledException {
		try {
			monitor.beginTask(CodeManipulationMessages.getFormattedString("AddGetterSetterOperation.processField", field.getElementName()), 2); //$NON-NLS-1$
	
			fSkipAllFinalSetters= (fSkipFinalSettersQuery == null);
			fSkipAllExisting= (fSkipExistingQuery == null);
	
			String fieldName= field.getElementName();
			String accessorName= evalAccessorName(fieldName);
			String argname= getArgumentName(accessorName);
			
			boolean isStatic= Flags.isStatic(field.getFlags());
			boolean isFinal= Flags.isFinal(field.getFlags());
			
			String typeName= Signature.toString(field.getTypeSignature());
			
			IType parentType= field.getDeclaringType();
			
			boolean addComments= CodeGenerationPreferencePage.doCreateComments();
			
			// test if the getter already exists
			String getterName= "get" + accessorName; //$NON-NLS-1$
			IMethod existingGetter= JavaModelUtil.findMethod(getterName, new String[0], false, parentType);
			
			String setterName= "set" + accessorName; //$NON-NLS-1$
			String[] args= new String[] { field.getTypeSignature() };		
			IMethod existingSetter= JavaModelUtil.findMethod(setterName, args, false, parentType);			
			
			boolean doCreateGetter= (existingGetter == null) || !querySkipExistingMethods(existingGetter);
			boolean doCreateSetter= (!isFinal || !querySkipFinalSetters(field)) && (existingSetter == null || querySkipExistingMethods(existingSetter));
						
			String lineDelim= StubUtility.getLineDelimiterUsed(parentType);
			int indent= StubUtility.getIndentUsed(field);
			
			if (doCreateGetter) {			
				// create the getter stub
				StringBuffer buf= new StringBuffer();
				if (addComments) {
					buf.append("/**\n"); //$NON-NLS-1$
					buf.append(" * Gets the "); buf.append(argname); buf.append(".\n"); //$NON-NLS-1$ //$NON-NLS-2$
					buf.append(" * @return Returns a "); buf.append(typeName); buf.append('\n'); //$NON-NLS-1$
					buf.append(" */\n"); //$NON-NLS-1$
				}
				buf.append("public "); //$NON-NLS-1$
				if (isStatic) {
					buf.append("static "); //$NON-NLS-1$
				}
				buf.append(typeName);
				buf.append(' '); buf.append(getterName);
				buf.append("() {\nreturn "); buf.append(fieldName); buf.append(";\n}\n"); //$NON-NLS-2$ //$NON-NLS-1$
				
				IJavaElement sibling= null;
				if (existingGetter != null) {
					sibling= StubUtility.findNextSibling(existingGetter);
					existingGetter.delete(false, null);
				}				
				
				String formattedContent= StubUtility.codeFormat(buf.toString(), indent, lineDelim) + lineDelim;
				fCreatedAccessors.add(parentType.createMethod(formattedContent, sibling, true, null));
			}
			
			monitor.worked(1);
									

			if (doCreateSetter) {
				// create the setter stub
				StringBuffer buf= new StringBuffer();
				if (addComments) {
					buf.append("/**\n"); //$NON-NLS-1$
					buf.append(" * Sets the "); buf.append(argname); buf.append(".\n"); //$NON-NLS-1$ //$NON-NLS-2$
					buf.append(" * @param "); buf.append(argname); buf.append(" The "); buf.append(argname); buf.append(" to set\n"); //$NON-NLS-3$ //$NON-NLS-1$ //$NON-NLS-2$
					buf.append(" */\n"); //$NON-NLS-1$
				}
				buf.append("public "); //$NON-NLS-1$
				if (isStatic) {
					buf.append("static "); //$NON-NLS-1$
				}
				buf.append("void "); buf.append(setterName); //$NON-NLS-1$
				buf.append('('); buf.append(typeName); buf.append(' '); 
				buf.append(argname); buf.append(") {\n"); //$NON-NLS-1$
				if (argname.equals(fieldName)) {
					if (isStatic) {
						buf.append(parentType.getElementName());
						buf.append('.');
					} else {
						buf.append("this."); //$NON-NLS-1$
					}
				}
				buf.append(fieldName); buf.append("= "); buf.append(argname); buf.append(";\n}\n"); //$NON-NLS-1$ //$NON-NLS-2$
				
				IJavaElement sibling= null;
				if (existingSetter != null) {
					sibling= StubUtility.findNextSibling(existingSetter);
					existingSetter.delete(false, null);
				}
				
				String formattedContent= StubUtility.codeFormat(buf.toString(), indent, lineDelim) + lineDelim;
				fCreatedAccessors.add(parentType.createMethod(formattedContent, sibling, true, null));
			}
		} finally {
			monitor.done();
		}
	}
	

	/**
	 * Returns the created accessors. To be called after a sucessful run.
	 */
	public IMethod[] getCreatedAccessors() {
		return (IMethod[]) fCreatedAccessors.toArray(new IMethod[fCreatedAccessors.size()]);
	}
}
