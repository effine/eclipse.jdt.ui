/*
 * (c) Copyright IBM Corp. 2000, 2001.
 * All Rights Reserved.
 */
package org.eclipse.jdt.internal.ui.typehierarchy;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.ControlAdapter;
import org.eclipse.swt.events.ControlEvent;
import org.eclipse.swt.events.DisposeEvent;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.ScrollBar;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;

import org.eclipse.jface.action.IMenuListener;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.action.ToolBarManager;
import org.eclipse.jface.viewers.DoubleClickEvent;
import org.eclipse.jface.viewers.IDoubleClickListener;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerSorter;

import org.eclipse.ui.IMemento;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartSite;

import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.JavaModelException;

import org.eclipse.jdt.ui.IContextMenuConstants;
import org.eclipse.jdt.ui.JavaElementLabelProvider;

import org.eclipse.jdt.internal.ui.JavaPlugin;
import org.eclipse.jdt.internal.ui.JavaPluginImages;
import org.eclipse.jdt.internal.ui.actions.ContextMenuGroup;
import org.eclipse.jdt.internal.ui.actions.GenerateGroup;
import org.eclipse.jdt.internal.ui.actions.OpenSourceReferenceAction;
import org.eclipse.jdt.internal.ui.search.JavaSearchGroup;
import org.eclipse.jdt.internal.ui.util.ExceptionHandler;
import org.eclipse.jdt.internal.ui.viewsupport.JavaTextLabelProvider;


/**
 * Method viewer shows a list of methods of a input type. 
 * Offers filter actions.
 * No dependency to the type hierarchy view
 */
public class MethodsViewer extends TableViewer {
	
	/**
	 * Sorter that uses the unmodified labelprovider (No declaring class names)
	 */
	private static class MethodsViewerSorter extends ViewerSorter {
		private JavaTextLabelProvider fLabelProvider;
		
		public MethodsViewerSorter() {
			fLabelProvider= new JavaTextLabelProvider(JavaElementLabelProvider.SHOW_DEFAULT);
		}
		
		public boolean isSorterProperty(Object element, Object property) {
			// doesn't matter, only world changed is used.
			return true;
		}
		
		public int category(Object element) {
			if (element instanceof IMethod) {
				try {
					if (((IMethod)element).isConstructor()) {
						return 1;
					} else {
						return 2;
					}
				} catch (JavaModelException e) {
					JavaPlugin.log(e.getStatus());
				}
			}
			return 0;
		}
		
		public int compare(Viewer viewer, Object e1, Object e2) {
			int cat1 = category(e1);
			int cat2 = category(e2);

			if (cat1 != cat2)
				return cat1 - cat2;

			// cat1 == cat2
			String name1= fLabelProvider.getTextLabel((IJavaElement)e1);
			String name2= fLabelProvider.getTextLabel((IJavaElement)e2);
			return getCollator().compare(name1, name2);
		}
	}
	
	

	private static final String TAG_HIDEFIELDS= "hidefields"; //$NON-NLS-1$
	private static final String TAG_HIDESTATIC= "hidestatic"; //$NON-NLS-1$
	private static final String TAG_HIDENONPUBLIC= "hidenonpublic"; //$NON-NLS-1$
	private static final String TAG_SHOWINHERITED= "showinherited";		 //$NON-NLS-1$
	private static final String TAG_VERTICAL_SCROLL= "mv_vertical_scroll";		 //$NON-NLS-1$
	
	private MethodsViewerFilterAction[] fFilterActions;
	private MethodsViewerFilter fFilter;
		
	private OpenSourceReferenceAction fOpen;

	private ShowInheritedMembersAction fShowInheritedMembersAction;

	private ContextMenuGroup[] fStandardGroups;	
	
	public MethodsViewer(Composite parent, IWorkbenchPart part) {
		super(new Table(parent, SWT.MULTI));
		
		final Table table= getTable();
		final TableColumn column= new TableColumn(table, SWT.NULL | SWT.MULTI | SWT.FULL_SELECTION);
		table.addControlListener(new ControlAdapter() {
			public void controlResized(ControlEvent e) {
				column.setWidth(table.getSize().x-2*table.getBorderWidth());
			}
		});

		MethodsContentProvider contentProvider= new MethodsContentProvider();
		setLabelProvider(new JavaElementLabelProvider(JavaElementLabelProvider.SHOW_DEFAULT));
		setContentProvider(contentProvider);
				
		fOpen= new OpenSourceReferenceAction(this);
		
		addDoubleClickListener(new IDoubleClickListener() {
			public void doubleClick(DoubleClickEvent event) {
				fOpen.run();
			}
		});
				
		fFilter= new MethodsViewerFilter();
		
		// fields
		String title= TypeHierarchyMessages.getString("MethodsViewer.hide_fields.label"); //$NON-NLS-1$
		MethodsViewerFilterAction hideFields= new MethodsViewerFilterAction(this, title, MethodsViewerFilter.FILTER_FIELDS, false);
		hideFields.setDescription(TypeHierarchyMessages.getString("MethodsViewer.hide_fields.description")); //$NON-NLS-1$
		hideFields.setToolTipChecked(TypeHierarchyMessages.getString("MethodsViewer.hide_fields.tooltip.checked")); //$NON-NLS-1$
		hideFields.setToolTipUnchecked(TypeHierarchyMessages.getString("MethodsViewer.hide_fields.tooltip.unchecked")); //$NON-NLS-1$
		JavaPluginImages.setImageDescriptors(hideFields, "lcl16", "fields_co.gif"); //$NON-NLS-2$ //$NON-NLS-1$
		
		// static
		title= TypeHierarchyMessages.getString("MethodsViewer.hide_static.label"); //$NON-NLS-1$
		MethodsViewerFilterAction hideStatic= new MethodsViewerFilterAction(this, title, MethodsViewerFilter.FILTER_STATIC, false);
		hideStatic.setDescription(TypeHierarchyMessages.getString("MethodsViewer.hide_static.description")); //$NON-NLS-1$
		hideStatic.setToolTipChecked(TypeHierarchyMessages.getString("MethodsViewer.hide_static.tooltip.checked")); //$NON-NLS-1$
		hideStatic.setToolTipUnchecked(TypeHierarchyMessages.getString("MethodsViewer.hide_static.tooltip.unchecked")); //$NON-NLS-1$
		JavaPluginImages.setImageDescriptors(hideStatic, "lcl16", "static_co.gif"); //$NON-NLS-2$ //$NON-NLS-1$
		
		// non-public
		title= TypeHierarchyMessages.getString("MethodsViewer.hide_nonpublic.label"); //$NON-NLS-1$
		MethodsViewerFilterAction hideNonPublic= new MethodsViewerFilterAction(this, title, MethodsViewerFilter.FILTER_NONPUBLIC, false);
		hideNonPublic.setDescription(TypeHierarchyMessages.getString("MethodsViewer.hide_nonpublic.description")); //$NON-NLS-1$
		hideNonPublic.setToolTipChecked(TypeHierarchyMessages.getString("MethodsViewer.hide_nonpublic.tooltip.checked")); //$NON-NLS-1$
		hideNonPublic.setToolTipUnchecked(TypeHierarchyMessages.getString("MethodsViewer.hide_nonpublic.tooltip.unchecked")); //$NON-NLS-1$
		JavaPluginImages.setImageDescriptors(hideNonPublic, "lcl16", "public_co.gif"); //$NON-NLS-2$ //$NON-NLS-1$
		
		// order corresponds to order in toolbar
		fFilterActions= new MethodsViewerFilterAction[] { hideFields, hideStatic, hideNonPublic };
		
		
		addFilter(fFilter);
		
		fShowInheritedMembersAction= new ShowInheritedMembersAction(this, false);
		showInheritedMethods(false);
		
		fStandardGroups= new ContextMenuGroup[] {
			new JavaSearchGroup(), new GenerateGroup()
		};
		
		setSorter(new MethodsViewerSorter());
			
	}
	
	/**
	 * Show inherited methods
	 */
	public void showInheritedMethods(boolean on) {
		MethodsContentProvider cprovider= (MethodsContentProvider) getContentProvider();
		try {
			cprovider.showInheritedMethods(on);
			fShowInheritedMembersAction.setChecked(on);
			
			JavaElementLabelProvider lprovider= (JavaElementLabelProvider) getLabelProvider();
			if (on) {
				lprovider.turnOn(JavaElementLabelProvider.SHOW_CONTAINER);
			} else {
				lprovider.turnOff(JavaElementLabelProvider.SHOW_CONTAINER);
			}
			refresh();
		} catch (JavaModelException e) {
			ExceptionHandler.handle(e, getControl().getShell(), TypeHierarchyMessages.getString("MethodsViewer.toggle.error.title"), TypeHierarchyMessages.getString("MethodsViewer.toggle.error.message")); //$NON-NLS-2$ //$NON-NLS-1$
		}
	}
	
	public boolean isShowInheritedMethods() {
		return ((MethodsContentProvider) getContentProvider()).isShowInheritedMethods();
	}

	/**
	 * Filters the method list
	 */	
	public void setMemberFilter(int filterProperty, boolean set) {
		fFilter.setFilter(filterProperty, set);
		for (int i= 0; i < fFilterActions.length; i++) {
			if (fFilterActions[i].getFilterProperty() == filterProperty) {
				fFilterActions[i].setChecked(set);
			}
		}
		refresh();
	}
	
	/**
	 * Saves the state of the filter actions
	 */
	public void saveState(IMemento memento) {
		memento.putString(TAG_HIDEFIELDS, String.valueOf(fFilter.hasFilter(MethodsViewerFilter.FILTER_FIELDS)));
		memento.putString(TAG_HIDESTATIC, String.valueOf(fFilter.hasFilter(MethodsViewerFilter.FILTER_STATIC)));
		memento.putString(TAG_HIDENONPUBLIC, String.valueOf(fFilter.hasFilter(MethodsViewerFilter.FILTER_NONPUBLIC)));
		memento.putString(TAG_SHOWINHERITED, String.valueOf(isShowInheritedMethods()));

		ScrollBar bar= getTable().getVerticalBar();
		int position= bar != null ? bar.getSelection() : 0;
		memento.putString(TAG_VERTICAL_SCROLL, String.valueOf(position));
	}

	/**
	 * Restores the state of the filter actions
	 */	
	public void restoreState(IMemento memento) {
		boolean set= Boolean.valueOf(memento.getString(TAG_HIDEFIELDS)).booleanValue();
		setMemberFilter(MethodsViewerFilter.FILTER_FIELDS, set);
		set= Boolean.valueOf(memento.getString(TAG_HIDESTATIC)).booleanValue();
		setMemberFilter(MethodsViewerFilter.FILTER_STATIC, set);
		set= Boolean.valueOf(memento.getString(TAG_HIDENONPUBLIC)).booleanValue();
		setMemberFilter(MethodsViewerFilter.FILTER_NONPUBLIC, set);		
		
		set= Boolean.valueOf(memento.getString(TAG_SHOWINHERITED)).booleanValue();
		showInheritedMethods(set);
		
		ScrollBar bar= getTable().getVerticalBar();
		if (bar != null) {
			Integer vScroll= memento.getInteger(TAG_VERTICAL_SCROLL);
			if (vScroll != null) {
				bar.setSelection(vScroll.intValue());
			}
		}
	}
	
	/**
	 * Attaches a contextmenu listener to the table
	 */
	public void initContextMenu(IMenuListener menuListener, String popupId, IWorkbenchPartSite viewSite) {
		MenuManager menuMgr= new MenuManager();
		menuMgr.setRemoveAllWhenShown(true);
		menuMgr.addMenuListener(menuListener);
		Menu menu= menuMgr.createContextMenu(getTable());
		getTable().setMenu(menu);
		viewSite.registerContextMenu(popupId, menuMgr, this);
	}	
		
	protected void handleDispose(DisposeEvent event) {
		Menu menu= getTable().getMenu();
		if (menu != null)
			menu.dispose();
		super.handleDispose(event);
	}
	
	/**
	 * Fills up the context menu with items for the method viewer
	 * Should be called by the creator of the context menu
	 */	
	public void contributeToContextMenu(IMenuManager menu) {	
		menu.appendToGroup(IContextMenuConstants.GROUP_OPEN, fOpen);	
		ContextMenuGroup.add(menu, fStandardGroups, this);
	}

	/**
	 * Fills up the tool bar with items for the method viewer
	 * Should be called by the creator of the tool bar
	 */
	public void contributeToToolBar(ToolBarManager tbm) {
		tbm.add(fShowInheritedMembersAction);
		tbm.add(new Separator());
		tbm.add(fFilterActions[0]); // fields
		tbm.add(fFilterActions[1]); // static
		tbm.add(fFilterActions[2]); // public
	}

}