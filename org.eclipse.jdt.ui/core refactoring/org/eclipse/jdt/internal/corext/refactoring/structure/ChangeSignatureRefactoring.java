package org.eclipse.jdt.internal.corext.refactoring.structure;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.SubProgressMonitor;

import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.Signature;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ConstructorInvocation;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.SuperConstructorInvocation;
import org.eclipse.jdt.core.dom.SuperMethodInvocation;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.VariableDeclaration;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.SearchEngine;

import org.eclipse.jdt.internal.corext.Assert;
import org.eclipse.jdt.internal.corext.dom.ASTNodes;
import org.eclipse.jdt.internal.corext.dom.ASTRewrite;
import org.eclipse.jdt.internal.corext.dom.Selection;
import org.eclipse.jdt.internal.corext.dom.SelectionAnalyzer;
import org.eclipse.jdt.internal.corext.refactoring.Checks;
import org.eclipse.jdt.internal.corext.refactoring.CompositeChange;
import org.eclipse.jdt.internal.corext.refactoring.ParameterInfo;
import org.eclipse.jdt.internal.corext.refactoring.RefactoringCoreMessages;
import org.eclipse.jdt.internal.corext.refactoring.base.Context;
import org.eclipse.jdt.internal.corext.refactoring.base.IChange;
import org.eclipse.jdt.internal.corext.refactoring.base.JavaSourceContext;
import org.eclipse.jdt.internal.corext.refactoring.base.Refactoring;
import org.eclipse.jdt.internal.corext.refactoring.base.RefactoringStatus;
import org.eclipse.jdt.internal.corext.refactoring.changes.TextChange;
import org.eclipse.jdt.internal.corext.refactoring.rename.MethodChecks;
import org.eclipse.jdt.internal.corext.refactoring.rename.ProblemNodeFinder;
import org.eclipse.jdt.internal.corext.refactoring.rename.RefactoringAnalyzeUtil;
import org.eclipse.jdt.internal.corext.refactoring.rename.RefactoringScopeFactory;
import org.eclipse.jdt.internal.corext.refactoring.rename.RippleMethodFinder;
import org.eclipse.jdt.internal.corext.refactoring.rename.TempOccurrenceFinder;
import org.eclipse.jdt.internal.corext.refactoring.util.JavaElementUtil;
import org.eclipse.jdt.internal.corext.refactoring.util.JdtFlags;
import org.eclipse.jdt.internal.corext.refactoring.util.ResourceUtil;
import org.eclipse.jdt.internal.corext.refactoring.util.TextChangeManager;
import org.eclipse.jdt.internal.corext.refactoring.util.WorkingCopyUtil;
import org.eclipse.jdt.internal.corext.textmanipulation.GroupDescription;
import org.eclipse.jdt.internal.corext.textmanipulation.MultiTextEdit;
import org.eclipse.jdt.internal.corext.textmanipulation.TextBuffer;
import org.eclipse.jdt.internal.corext.textmanipulation.TextEdit;
import org.eclipse.jdt.internal.corext.util.JavaModelUtil;
import org.eclipse.jdt.internal.ui.JavaPlugin;

public class ChangeSignatureRefactoring extends Refactoring {
	
	private final List fParameterInfos;
	private TextChangeManager fChangeManager;
	private IMethod fMethod;
	private IMethod[] fRippleMethods;
	private ASTNodeMappingManager fAstManager;
	private ASTRewriteManager fRewriteManager;
	private ASTNode[] fOccurrenceNodes;
	private Set fDescriptionGroups;
	private int fVisibility;
	private static final String CONST_CLASS_DECL = "class A{";//$NON-NLS-1$
	private static final String CONST_ASSIGN = " i=";		//$NON-NLS-1$
	private static final String CONST_CLOSE = ";}";			//$NON-NLS-1$
	private static final Collection KEYWORD_TYPE_NAMES= Arrays.asList(new String[]{
                                                           	"boolean",  //$NON-NLS-1$
                                                           	"byte",		//$NON-NLS-1$
															"char", 	//$NON-NLS-1$
															"double", 	//$NON-NLS-1$
															"float",	//$NON-NLS-1$
															"int", 		//$NON-NLS-1$
															"long", 	//$NON-NLS-1$
															"short"});	//$NON-NLS-1$

	public ChangeSignatureRefactoring(IMethod method){
		fMethod= method;
		fParameterInfos= createParameterInfoList(method);
		fAstManager= new ASTNodeMappingManager();
		fRewriteManager= new ASTRewriteManager(fAstManager);
		fDescriptionGroups= new HashSet(0);
		try {
			fVisibility= getInitialMethodVisibility();
		} catch (JavaModelException e) {
			JavaPlugin.log(e);
			fVisibility= JdtFlags.VISIBILITY_CODE_INVALID;
		}
	}
	
	private int getInitialMethodVisibility() throws JavaModelException{
		return JdtFlags.getVisibilityCode(fMethod);
	}
	
	private static List createParameterInfoList(IMethod method) {
		try {
			String[] typeNames= method.getParameterTypes();
			String[] oldNames= method.getParameterNames();
			List result= new ArrayList(typeNames.length);
			for (int i= 0; i < oldNames.length; i++){
				result.add(new ParameterInfo(Signature.toString(typeNames[i]), oldNames[i], i));
			}
			return result;
		} catch(JavaModelException e) {
			JavaPlugin.log(e);
			return new ArrayList(0);
		}		
	}

	/*
	 * @see org.eclipse.jdt.internal.corext.refactoring.base.IRefactoring#getName()
	 */
	public String getName() {
		return RefactoringCoreMessages.getString("ChangeSignatureRefactoring.modify_Parameters"); //$NON-NLS-1$
	}
	
	public IMethod getMethod() {
		return fMethod;
	}
	
	/*
	 * @see JdtFlags
	 */
	public int getVisibility(){
		return fVisibility;
	}

	/*
	 * @see JdtFlags
	 */	
	public void setVisibility(int visibility){
		Assert.isTrue(	visibility == JdtFlags.VISIBILITY_CODE_PUBLIC ||
		            	visibility == JdtFlags.VISIBILITY_CODE_PROTECTED ||
		            	visibility == JdtFlags.VISIBILITY_CODE_PACKAGE ||
		            	visibility == JdtFlags.VISIBILITY_CODE_PRIVATE);  
		fVisibility= visibility;            	
	}
	
	/*
	 * @see JdtFlags
	 */	
	public int[] getAvailableVisibilities() throws JavaModelException{
		if (fMethod.getDeclaringType().isInterface())
			return new int[]{JdtFlags.VISIBILITY_CODE_PUBLIC};
		else 	
			return new int[]{	JdtFlags.VISIBILITY_CODE_PUBLIC,
								JdtFlags.VISIBILITY_CODE_PROTECTED,
								JdtFlags.VISIBILITY_CODE_PACKAGE,
								JdtFlags.VISIBILITY_CODE_PRIVATE};
	}
	
	/**
	 * 	 * @return List of <code>ParameterInfo</code> objects.	 */
	public List getParameterInfos(){
		return fParameterInfos;
	}
	
	public RefactoringStatus checkParameters() throws JavaModelException{
		if (fMethod.getNumberOfParameters() == 0 && fParameterInfos.isEmpty() && isVisibilitySameAsInitial())
			return RefactoringStatus.createFatalErrorStatus("No parameters were added and visibility is unchanged");
		if (areNamesSameAsInitial() && isOrderSameAsInitial() && isVisibilitySameAsInitial())
			return RefactoringStatus.createFatalErrorStatus(RefactoringCoreMessages.getString("ChangeSignatureRefactoring.no_changes")); //$NON-NLS-1$
		RefactoringStatus result= new RefactoringStatus();
		checkForDuplicateNames(result);
		if (result.hasFatalError())
			return result;
		checkAllNames(result);
		if (result.hasFatalError())
			return result;
		checkAddedParameters(result);	
		return result;
	}

	private void checkAddedParameters(RefactoringStatus result) {
		for (Iterator iter= fParameterInfos.iterator(); iter.hasNext();) {
			ParameterInfo info= (ParameterInfo) iter.next();
			if (! info.isAdded())
				continue;
			checkParameterType(result, info);
			if (result.hasFatalError())
				return;
			checkParameterDefaultValue(result, info);
		}
	}

	private void checkParameterDefaultValue(RefactoringStatus result, ParameterInfo info) {
		if (! isValidExpression(info.getDefaultValue())){
			String pattern= "''{0}'' is not a valid expression";
			String msg= MessageFormat.format(pattern, new String[]{info.getDefaultValue()});
			result.addFatalError(msg);
		}	
	}

	private void checkParameterType(RefactoringStatus result, ParameterInfo info) {
		if (! isValidTypeName(info.getType())){
			String pattern= "''{0}'' is not a valid type name";
			String msg= MessageFormat.format(pattern, new String[]{info.getType()});
			result.addFatalError(msg);
		}	
	}

	private static boolean isValidTypeName(String string){
		if ("".equals(string.trim())) //speed up for a common case
			return false;
		if (! Checks.checkTypeName(string).hasFatalError())
			return true;
		if (KEYWORD_TYPE_NAMES.contains(string))
			return true;	
		StringBuffer cuBuff= new StringBuffer();
		cuBuff.append(CONST_CLASS_DECL);
		int offset= cuBuff.length();
		cuBuff.append(string)
			  .append(CONST_ASSIGN)
			  .append("null")
			  .append(CONST_CLOSE);
		CompilationUnit cu= AST.parseCompilationUnit(cuBuff.toString().toCharArray());
		Selection selection= Selection.createFromStartLength(offset, string.length());
		SelectionAnalyzer analyzer= new SelectionAnalyzer(selection, false);
		cu.accept(analyzer);
		ASTNode selected= analyzer.getFirstSelectedNode();
		return (selected instanceof Type) && 
				string.equals(cuBuff.substring(selected.getStartPosition(), ASTNodes.getExclusiveEnd(selected)));
	}
	
	private static boolean isValidExpression(String string){
		if ("".equals(string.trim())) //speed up for a common case
			return false;
		StringBuffer cuBuff= new StringBuffer();
		cuBuff.append(CONST_CLASS_DECL)
			  .append("Object")
			  .append(CONST_ASSIGN);
		int offset= cuBuff.length();
		cuBuff.append(string)
			  .append(CONST_CLOSE);
		CompilationUnit cu= AST.parseCompilationUnit(cuBuff.toString().toCharArray());
		Selection selection= Selection.createFromStartLength(offset, string.length());
		SelectionAnalyzer analyzer= new SelectionAnalyzer(selection, false);
		cu.accept(analyzer);
		ASTNode selected= analyzer.getFirstSelectedNode();
		return (selected instanceof Expression) && 
				string.equals(cuBuff.substring(selected.getStartPosition(), ASTNodes.getExclusiveEnd(selected)));
	}

	public RefactoringStatus checkPreactivation() throws JavaModelException{
		RefactoringStatus result= new RefactoringStatus();
		result.merge(Checks.checkAvailability(fMethod));
		if (result.hasFatalError())
			return result;
			
		//XXX disable for constructors  - broken. see bug 23585
//		if (fMethod.isConstructor())
//			return RefactoringStatus.createFatalErrorStatus("This refactoring is not implemented for constructors");

		return result;
	}
	
	/*
	 * @see org.eclipse.jdt.internal.corext.refactoring.base.Refactoring#checkActivation(org.eclipse.core.runtime.IProgressMonitor)
	 */
	public RefactoringStatus checkActivation(IProgressMonitor pm) throws JavaModelException {
		try{
			pm.beginTask("", 2); //$NON-NLS-1$
			RefactoringStatus result= Checks.checkIfCuBroken(fMethod);
			if (result.hasFatalError())
				return result;
			IMethod orig= (IMethod)WorkingCopyUtil.getOriginal(fMethod);
			if (orig == null || ! orig.exists()){
				String message= RefactoringCoreMessages.getFormattedString("ChangeSignatureRefactoring.method_deleted", fMethod.getCompilationUnit().getElementName());//$NON-NLS-1$
				return RefactoringStatus.createFatalErrorStatus(message);
			}
			fMethod= orig;
			
			if (MethodChecks.isVirtual(fMethod)){
				result.merge(MethodChecks.checkIfComesFromInterface(getMethod(), new SubProgressMonitor(pm, 1)));
				if (result.hasFatalError())
					return result;	
				
				result.merge(MethodChecks.checkIfOverridesAnother(getMethod(), new SubProgressMonitor(pm, 1)));
				if (result.hasFatalError())
					return result;			
			} 
			if (fMethod.getDeclaringType().isInterface()){
				result.merge(MethodChecks.checkIfOverridesAnother(getMethod(), new SubProgressMonitor(pm, 1)));
				if (result.hasFatalError())
					return result;
			}
				
			return result;
		} finally{
			pm.done();
		}
	}

	/*
	 * @see org.eclipse.jdt.internal.corext.refactoring.base.Refactoring#checkInput(org.eclipse.core.runtime.IProgressMonitor)
	 */
	public RefactoringStatus checkInput(IProgressMonitor pm) throws JavaModelException {
		try{
			pm.beginTask(RefactoringCoreMessages.getString("ChangeSignatureRefactoring.checking_preconditions"), 5); //$NON-NLS-1$
			RefactoringStatus result= new RefactoringStatus();
			result.merge(checkParameters());
			if (result.hasFatalError())
				return result;

			fRippleMethods= RippleMethodFinder.getRelatedMethods(fMethod, new SubProgressMonitor(pm, 1), null);
			fOccurrenceNodes= getOccurrenceNodes(new SubProgressMonitor(pm, 1));
			
			result.merge(checkVisibilityChanges());
					
			if (! isOrderSameAsInitial())	
				result.merge(checkReorderings(new SubProgressMonitor(pm, 1)));	
			else pm.worked(1);
			
			if (result.hasFatalError())
				return result;

			fChangeManager= createChangeManager(new SubProgressMonitor(pm, 1));

			if (mustAnalyzeAst()) 
				result.merge(analyzeAst()); 
			if (result.hasFatalError())
				return result;

			result.merge(validateModifiesFiles());
			return result;
		} catch (CoreException e){	
			throw new JavaModelException(e);
		} finally{
			pm.done();
		}
	}

	private RefactoringStatus checkVisibilityChanges() throws JavaModelException {
		if (isVisibilitySameAsInitial())
			return null;
	    if (fRippleMethods.length == 1)
	    	return null;
	    Assert.isTrue(getInitialMethodVisibility() != JdtFlags.VISIBILITY_CODE_PRIVATE);
	    if (fVisibility == JdtFlags.VISIBILITY_CODE_PRIVATE)
	    	return RefactoringStatus.createWarningStatus("Changing visibility to 'private' will make this method non-virtual, which may affect the program's behavior");
		return null;
	}
	
	public String getMethodSignaturePreview() throws JavaModelException{
		StringBuffer buff= new StringBuffer();
		
		buff.append(getPreviewOfVisibityString());
		if (! getMethod().isConstructor())
			buff.append(getReturnTypeString());

		buff.append(getMethod().getElementName())
			.append(Signature.C_PARAM_START)
			.append(getMethodParameters())
			.append(Signature.C_PARAM_END);
		return buff.toString();
	}

	public String getPreviewOfVisibityString() {
		String visibilityString= JdtFlags.getVisibilityString(fVisibility);
		if ("".equals(visibilityString))
			return visibilityString;
		return visibilityString + ' ';
	}

	private void checkForDuplicateNames(RefactoringStatus result){
		Set found= new HashSet();
		Set doubled= new HashSet();
		for (Iterator iter = getParameterInfos().iterator(); iter.hasNext();) {
			ParameterInfo info= (ParameterInfo)iter.next();
			String newName= info.getNewName();
			if (found.contains(newName) && !doubled.contains(newName)){
				result.addFatalError(RefactoringCoreMessages.getFormattedString("ChangeSignatureRefactoring.duplicate_name", newName));//$NON-NLS-1$	
				doubled.add(newName);
			} else {
				found.add(newName);
			}	
		}
	}
	
	private void  checkAllNames(RefactoringStatus result){
		for (Iterator iter = getParameterInfos().iterator(); iter.hasNext();) {
			ParameterInfo info= (ParameterInfo)iter.next();
			String newName= info.getNewName();
			result.merge(Checks.checkFieldName(newName));	
			if (! Checks.startsWithLowerCase(newName))
				result.addWarning(RefactoringCoreMessages.getString("ChangeSignatureRefactoring.should_start_lowercase")); //$NON-NLS-1$
		}
	}

	private ICompilationUnit getCu() {
		return WorkingCopyUtil.getWorkingCopyIfExists(fMethod.getCompilationUnit());
	}
	
	private boolean mustAnalyzeAst() throws JavaModelException{
		if (JdtFlags.isAbstract(getMethod()))
			return false;
		else if (JdtFlags.isNative(getMethod()))
			return false;
		else if (getMethod().getDeclaringType().isInterface())
			return false;
		else 
			return true;
	}
	
	private RefactoringStatus analyzeAst() throws JavaModelException{		
		try {
			RefactoringStatus result= new RefactoringStatus();
						
			CompilationUnit compliationUnitNode= fAstManager.getAST(getCu());
			TextChange change= fChangeManager.get(getCu());
			String newCuSource= change.getPreviewContent();
			CompilationUnit newCUNode= AST.parseCompilationUnit(newCuSource.toCharArray(), getCu().getElementName(), getCu().getJavaProject());
			result.merge(RefactoringAnalyzeUtil.analyzeIntroducedCompileErrors(newCuSource, newCUNode, compliationUnitNode));
			if (result.hasError())
				return result;

			ParameterInfo[] renamedInfos= getRenamedParameterNames();				
			for (int i= 0; i < renamedInfos.length; i++) {
				VariableDeclaration vd= getVariableDeclaration(renamedInfos[i], fMethod);
				String fullKey= RefactoringAnalyzeUtil.getFullBindingKey(vd);
				TextEdit[] paramRenameEdits= findEditGroupDescription(fDescriptionGroups, renamedInfos[i]).getTextEdits();
				SimpleName[] problemNodes= ProblemNodeFinder.getProblemNodes(newCUNode, paramRenameEdits, change, fullKey);
				result.merge(RefactoringAnalyzeUtil.reportProblemNodes(newCuSource, problemNodes));
			}
			return result;
		} catch(CoreException e) {
			throw new JavaModelException(e);
		}	
	}
	
	private static GroupDescription findEditGroupDescription(Set descriptions, ParameterInfo info){
		for (Iterator iter= descriptions.iterator(); iter.hasNext();) {
			GroupDescription desc= (GroupDescription) iter.next();
			if (desc.getName().equals(createGroupDescriptionString(info.getOldName())))
				return desc;
		}
		Assert.isTrue(false);
		return null;
	}
	
	private static String createGroupDescriptionString(String oldParamName){
		return "rename." + oldParamName;
	}
	
	private VariableDeclaration getVariableDeclaration(ParameterInfo info, IMethod method) throws JavaModelException {
		MethodDeclaration md= getDeclarationNode(method);
		for (Iterator iter= md.parameters().iterator(); iter.hasNext();) {
			SingleVariableDeclaration paramDecl= (SingleVariableDeclaration) iter.next();
			if (paramDecl.getName().getIdentifier().equals(info.getOldName()))
				return paramDecl;
		}
		Assert.isTrue(false);
		return null;
	}
	
	private String getReturnTypeString() throws IllegalArgumentException, JavaModelException {
		StringBuffer buff= new StringBuffer();
		String returnType = Signature.getReturnType(getMethod().getSignature());
		if (returnType.length() != 0) {
			buff.append(Signature.toString(returnType))
				  .append(' ');
		}
		return buff.toString();
	}

	private String getMethodParameters() throws JavaModelException {
		StringBuffer buff= new StringBuffer();
		int i= 0;
		for (Iterator iter= fParameterInfos.iterator(); iter.hasNext(); i++) {
			ParameterInfo info= (ParameterInfo) iter.next();
			if (i != 0)
				buff.append(", ");  //$NON-NLS-1$
			buff.append(createDeclarationString(info));
		}
		return buff.toString();
	}

	private boolean areNamesSameAsInitial() {
		for (Iterator iter= fParameterInfos.iterator(); iter.hasNext();) {
			ParameterInfo info= (ParameterInfo) iter.next();
			if (! info.getOldName().equals(info.getNewName()))
				return false;
		}
		return true;
	}

	private boolean isOrderSameAsInitial(){
		int i= 0;
		for (Iterator iter= fParameterInfos.iterator(); iter.hasNext(); i++) {
			ParameterInfo info= (ParameterInfo) iter.next();
			if (info.getOldIndex() != i)
				return false;
			if (info.isAdded())
				return false;
		}
		return true;
	}

	private RefactoringStatus checkReorderings(IProgressMonitor pm) throws JavaModelException {
		try{
			pm.beginTask(RefactoringCoreMessages.getString("ChangeSignatureRefactoring.checking_preconditions"), 2); //$NON-NLS-1$

			RefactoringStatus result= new RefactoringStatus();
			result.merge(checkNativeMethods());
			result.merge(checkParameterNamesInRippleMethods());
			return result;
		} finally{
			pm.done();
		}	
	}
	
	private RefactoringStatus checkParameterNamesInRippleMethods() throws JavaModelException {
		RefactoringStatus result= new RefactoringStatus();
		List newParameterNames= getNewParameterNamesList();
		for (int i= 0; i < fRippleMethods.length; i++) {
			String[] paramNames= fRippleMethods[i].getParameterNames();
			for (int j= 0; j < paramNames.length; j++) {
				if (newParameterNames.contains(paramNames[j])){
					String pattern= "Method ''{0}'' already has a parameter named ''{1}''";
					String[] args= new String[]{JavaElementUtil.createMethodSignature(fRippleMethods[i]), paramNames[j]};
					String msg= MessageFormat.format(pattern, args);
					Context context= JavaSourceContext.create(fRippleMethods[i].getCompilationUnit(), fRippleMethods[i].getNameRange());
					result.addError(msg, context);
				}	
			}
		}
		return result;
	}
	
	private List getNewParameterNamesList() {
		List newNames= new ArrayList(0);
		for (Iterator iter= fParameterInfos.iterator(); iter.hasNext();) {
			ParameterInfo info= (ParameterInfo) iter.next();
			if (info.isAdded())
				newNames.add(info.getNewName());
		}
		return newNames;
	}
	
	private RefactoringStatus checkNativeMethods() throws JavaModelException{
		RefactoringStatus result= new RefactoringStatus();
		for (int i= 0; i < fRippleMethods.length; i++) {
			if (JdtFlags.isNative(fRippleMethods[i])){
				String message= RefactoringCoreMessages.getFormattedString("ChangeSignatureRefactoring.native", //$NON-NLS-1$
					new String[]{JavaElementUtil.createMethodSignature(fRippleMethods[i]), JavaModelUtil.getFullyQualifiedName(fRippleMethods[i].getDeclaringType())});
				result.addError(message, JavaSourceContext.create(fRippleMethods[i]));			
			}								
		}
		return result;
	}

	private IFile[] getAllFilesToModify() throws CoreException{
		return ResourceUtil.getFiles(fChangeManager.getAllCompilationUnits());
	}
	
	private RefactoringStatus validateModifiesFiles() throws CoreException{
		return Checks.validateModifiesFiles(getAllFilesToModify());
	}

	//--  changes ----
	public IChange createChange(IProgressMonitor pm) throws JavaModelException {
		try{
			return new CompositeChange(RefactoringCoreMessages.getString("ChangeSignatureRefactoring.restructure_parameters"), fChangeManager.getAllChanges()); //$NON-NLS-1$
		} finally{
			pm.done();
		}	
	}

	private TextChangeManager createChangeManager(IProgressMonitor pm) throws CoreException {
		pm.beginTask(RefactoringCoreMessages.getString("ChangeSignatureRefactoring.preparing_preview"), 4); //$NON-NLS-1$

		if (! isVisibilitySameAsInitial())
			addVisibilityChanges(new SubProgressMonitor(pm, 1));

		if (! areNamesSameAsInitial())
			addRenamings(new SubProgressMonitor(pm, 1));
		else
			pm.worked(1);	

		addReorderings(new SubProgressMonitor(pm, 1));
		addNewParameters(new SubProgressMonitor(pm, 1));


		TextChangeManager manager= new TextChangeManager();
		CompilationUnit[] cuNodes= fRewriteManager.getAllCompilationUnitNodes();
		for (int i= 0; i < cuNodes.length; i++) {
			CompilationUnit cuNode= cuNodes[i];
			ASTRewrite rewrite= fRewriteManager.getRewrite(cuNode);
			TextBuffer textBuffer= TextBuffer.create(fAstManager.getCompilationUnit(cuNode).getBuffer().getContents());
			TextEdit resultingEdits= new MultiTextEdit();
			rewrite.rewriteNode(textBuffer, resultingEdits, fDescriptionGroups);
			manager.get(fAstManager.getCompilationUnit(cuNode)).addTextEdit("Modify parameters", resultingEdits);
		}
		
		return manager;
	}

	private void addVisibilityChanges(IProgressMonitor pm) throws CoreException {
		pm.beginTask("", fRippleMethods.length);
		for (int i= 0; i < fRippleMethods.length; i++) {
			IMethod method= fRippleMethods[i];
			if (needsVisibilityUpdate(method)){
				CompilationUnit cuNode= fAstManager.getAST(method.getCompilationUnit());
				MethodDeclaration md= getDeclarationNode(method);
				MethodDeclaration md1= md.getAST().newMethodDeclaration();
				md1.setModifiers(getNewModifiers(md));
				md1.setExtraDimensions(md.getExtraDimensions());
				fRewriteManager.getRewrite(cuNode).markAsModified(md, md1);				
			}	
			pm.worked(1);
		}
		pm.done();
	}
	
	private int getNewModifiers(MethodDeclaration md) {
		return clearAccessModifiers(md.getModifiers()) | getModifierFlag(fVisibility);
	}
	
	private int getModifierFlag(int visibility) {
		switch(visibility){
			case JdtFlags.VISIBILITY_CODE_PUBLIC: return Modifier.PUBLIC;
   		 	case JdtFlags.VISIBILITY_CODE_PRIVATE: return Modifier.PRIVATE;
   		 	case JdtFlags.VISIBILITY_CODE_PROTECTED: return Modifier.PROTECTED;
   		 	default: Assert.isTrue(false); return Modifier.NONE;
		}
	}
	
	private static int clearAccessModifiers(int flags) {
		return clearFlag(clearFlag(clearFlag(flags, Modifier.PRIVATE), Modifier.PUBLIC), Modifier.PROTECTED);
	}
	
	private static int clearFlag(int flags, int flag){
		return flags & ~ flag;
	}
	
	private MethodDeclaration getDeclarationNode(IMethod method) throws JavaModelException {
		return ASTNodeSearchUtil.getMethodDeclarationNode(method, fAstManager);
	}
		
	private boolean needsVisibilityUpdate(IMethod method) throws JavaModelException {
		if (isVisibilitySameAsInitial())
			return false;
		if (isIncreasingVisibility())
			return JdtFlags.isHigherVisibility(fVisibility, JdtFlags.getVisibilityCode(method));
		else
			return JdtFlags.isHigherVisibility(JdtFlags.getVisibilityCode(method), fVisibility);
	}

	private boolean isIncreasingVisibility() throws JavaModelException{
		return JdtFlags.isHigherVisibility(fVisibility, JdtFlags.getVisibilityCode(fMethod));
	}
	
	private boolean isVisibilitySameAsInitial() throws JavaModelException {
		return fVisibility == JdtFlags.getVisibilityCode(fMethod);
	}
	
	private ParameterInfo[] getRenamedParameterNames(){
		List result= new ArrayList();
		for (Iterator iterator = getParameterInfos().iterator(); iterator.hasNext();) {
			ParameterInfo info= (ParameterInfo)iterator.next();
			if (! info.isAdded() && ! info.getOldName().equals(info.getNewName()))
				result.add(info);
		}
		return (ParameterInfo[]) result.toArray(new ParameterInfo[result.size()]);
	}

	private IJavaSearchScope createRefactoringScope()  throws JavaModelException{
		if (fRippleMethods.length == 1)	
			return RefactoringScopeFactory.create(fRippleMethods[0]);
		return SearchEngine.createWorkspaceScope();
	}
	
	private ASTNode[] getOccurrenceNodes(IProgressMonitor pm) throws JavaModelException{
		return ASTNodeSearchUtil.findOccurrenceNodes(fRippleMethods, fAstManager, pm, createRefactoringScope());
	}
	
	private void addRenamings(IProgressMonitor pm) throws JavaModelException {
		MethodDeclaration methodDeclaration= ASTNodeSearchUtil.getMethodDeclarationNode(fMethod, fAstManager);
		ParameterInfo[] infos= getRenamedParameterNames();
		ICompilationUnit cu= WorkingCopyUtil.getWorkingCopyIfExists(fMethod.getCompilationUnit());
		if (cu == null)
			return;
		
		for (int i= 0; i < infos.length; i++) {
			ParameterInfo info= infos[i];
			SingleVariableDeclaration param= (SingleVariableDeclaration)methodDeclaration.parameters().get(info.getOldIndex());
			ASTNode[] paramOccurrences= TempOccurrenceFinder.findTempOccurrenceNodes(param, true, true);
			for (int j= 0; j < paramOccurrences.length; j++) {
				ASTNode occurence= paramOccurrences[j];
				if (occurence instanceof SimpleName){
					SimpleName newName= occurence.getAST().newSimpleName(info.getNewName());
					fRewriteManager.getRewrite(cu).markAsReplaced(occurence, newName, createGroupDescriptionString(info.getOldName()));
				}
			}
		}
	}
	
	private void addReorderings(IProgressMonitor pm) throws JavaModelException {
		if (fOccurrenceNodes == null)
			return;
		int[] permutation= getPermutation();
		for (int i= 0; i < fOccurrenceNodes.length; i++) {
			ASTNode node= fOccurrenceNodes[i];
			ICompilationUnit cu= WorkingCopyUtil.getWorkingCopyIfExists(fAstManager.getCompilationUnit(node));
			if (cu == null)
				continue;
			ASTNode[] nodes;
			if (isReferenceNode(node))
				nodes= (Expression[]) getArguments(node).toArray(new Expression[getArguments(node).size()]);
			else
				nodes= (SingleVariableDeclaration[]) getMethodDeclaration(node).parameters().toArray(new SingleVariableDeclaration[getMethodDeclaration(node).parameters().size()]);
			
			ASTNode[] newPermutation= new ASTNode[nodes.length];
			ASTRewrite rewrite= fRewriteManager.getRewrite(cu);
			for (int j= 0; j < nodes.length; j++) {
				if (permutation[j] == j)
					continue;
				ASTNode srcNode= nodes[permutation[j]];
				if (rewrite.isReplaced(srcNode))
					newPermutation[j]= rewrite.getReplacingNode(srcNode);
				else
					newPermutation[j]= rewrite.createCopy(srcNode);	
			}
			for (int j= 0; j < nodes.length; j++) {
				if (permutation[j] == j)
					continue;
				ASTNode destNode= nodes[j];
				rewrite.markAsReplaced(destNode, newPermutation[j]);
			}
		}	
	}
	
	private void addNewParameters(IProgressMonitor pm) throws CoreException {
		int i= 0;
		for (Iterator iter= fParameterInfos.iterator(); iter.hasNext(); i++) {
			ParameterInfo info= (ParameterInfo) iter.next();
			if (! info.isAdded())
				continue;
			for (int j= 0; j < fOccurrenceNodes.length; j++) {
				ASTNode node= fOccurrenceNodes[j];
				ICompilationUnit cu= WorkingCopyUtil.getWorkingCopyIfExists(fAstManager.getCompilationUnit(node));
				if (cu == null)
					continue;
				
				if (isReferenceNode(node)){
					Expression newArg= (Expression)fRewriteManager.getRewrite(cu).createPlaceholder(info.getDefaultValue(), ASTRewrite.EXPRESSION);
					getArguments(node).add(i, newArg);
					fRewriteManager.getRewrite(cu).markAsInserted(newArg);
				} else {
					SingleVariableDeclaration newP= node.getAST().newSingleVariableDeclaration();
					newP.setName(node.getAST().newSimpleName(info.getNewName()));
					newP.setType((Type)fRewriteManager.getRewrite(cu).createPlaceholder(info.getType(), ASTRewrite.TYPE));
					getMethodDeclaration(node).parameters().add(i, newP);
					fRewriteManager.getRewrite(cu).markAsInserted(newP);
				}	
			}
		}
	}

	private static MethodDeclaration getMethodDeclaration(ASTNode node){
		return (MethodDeclaration)ASTNodes.getParent(node, MethodDeclaration.class);
	}

	private static String createDeclarationString(ParameterInfo info) {
		return info.getType() + " " + info.getNewName();
	}

	private static List getArguments(ASTNode node) {
		if (node instanceof SimpleName && node.getParent() instanceof MethodInvocation)
			return ((MethodInvocation)node.getParent()).arguments();
		if (node instanceof SimpleName && node.getParent() instanceof SuperMethodInvocation)
			return ((SuperMethodInvocation)node.getParent()).arguments();
		if (node instanceof ExpressionStatement && isReferenceNode(((ExpressionStatement)node).getExpression()))
			return getArguments(((ExpressionStatement)node).getExpression());
		if (node instanceof MethodInvocation)	
			return ((MethodInvocation)node).arguments();
		if (node instanceof SuperMethodInvocation)	
			return ((SuperMethodInvocation)node).arguments();
		if (node instanceof ClassInstanceCreation)	
			return ((ClassInstanceCreation)node).arguments();
		if (node instanceof ConstructorInvocation)	
			return ((ConstructorInvocation)node).arguments();
		if (node instanceof SuperConstructorInvocation)	
			return ((SuperConstructorInvocation)node).arguments();
		return null;	
	}
	
	private static boolean isReferenceNode(ASTNode node){
		if (node instanceof SimpleName && node.getParent() instanceof MethodInvocation)
			return true;
		if (node instanceof SimpleName && node.getParent() instanceof SuperMethodInvocation)
			return true;
		if (node instanceof ExpressionStatement && isReferenceNode(((ExpressionStatement)node).getExpression()))
			return true;
		if (node instanceof MethodInvocation)	
			return true;
		if (node instanceof SuperMethodInvocation)	
			return true;
		if (node instanceof ClassInstanceCreation)	
			return true;
		if (node instanceof ConstructorInvocation)	
			return true;
		if (node instanceof SuperConstructorInvocation)	
			return true;
		return false;	
	}

	private int[] getPermutation() {
		List integers= new ArrayList(fParameterInfos.size());
		for (int i= 0, n= fParameterInfos.size(); i < n; i++) {
			ParameterInfo info= (ParameterInfo)fParameterInfos.get(i);
			if (! info.isAdded())
				integers.add(new Integer(info.getOldIndex()));
		}
		int[] result= new int[integers.size()];
		for (int i= 0; i < result.length; i++) {
			result[i]= ((Integer)integers.get(i)).intValue();
		}
		return result;
	}
}
