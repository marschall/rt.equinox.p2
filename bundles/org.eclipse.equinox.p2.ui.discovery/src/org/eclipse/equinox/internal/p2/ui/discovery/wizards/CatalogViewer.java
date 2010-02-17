/*******************************************************************************
 * Copyright (c) 2009 Tasktop Technologies and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Tasktop Technologies - initial API and implementation
 *******************************************************************************/
package org.eclipse.equinox.internal.p2.ui.discovery.wizards;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.eclipse.core.runtime.Assert;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IBundleGroup;
import org.eclipse.core.runtime.IBundleGroupProvider;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.MultiStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.equinox.internal.p2.discovery.Catalog;
import org.eclipse.equinox.internal.p2.discovery.model.CatalogCategory;
import org.eclipse.equinox.internal.p2.discovery.model.CatalogItem;
import org.eclipse.equinox.internal.p2.discovery.model.Tag;
import org.eclipse.equinox.internal.p2.discovery.util.CatalogCategoryComparator;
import org.eclipse.equinox.internal.p2.discovery.util.CatalogItemComparator;
import org.eclipse.equinox.internal.p2.ui.discovery.DiscoveryUi;
import org.eclipse.equinox.internal.p2.ui.discovery.util.ControlListItem;
import org.eclipse.equinox.internal.p2.ui.discovery.util.ControlListViewer;
import org.eclipse.equinox.internal.p2.ui.discovery.util.FilteredViewer;
import org.eclipse.equinox.internal.p2.ui.discovery.util.PatternFilter;
import org.eclipse.equinox.internal.p2.ui.discovery.util.SelectionProviderAdapter;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.operation.IRunnableContext;
import org.eclipse.jface.operation.IRunnableWithProgress;
import org.eclipse.jface.viewers.ISelectionChangedListener;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.StructuredViewer;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.jface.viewers.ViewerSorter;
import org.eclipse.jface.window.IShellProvider;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.DisposeEvent;
import org.eclipse.swt.events.DisposeListener;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.statushandlers.StatusManager;

/**
 * The main wizard page that allows users to select connectors that they wish to install.
 * 
 * @author David Green
 * @author Steffen Pingel
 */
public class CatalogViewer extends FilteredViewer {

	private class Filter extends ViewerFilter {

		@Override
		public boolean select(Viewer viewer, Object parentElement, Object element) {
			if (element instanceof CatalogItem) {
				return doFilter((CatalogItem) element);
			} else if (element instanceof CatalogCategory) {
				// only show categories if at least one child is visible
				CatalogCategory category = (CatalogCategory) element;
				for (CatalogItem item : category.getItems()) {
					if (doFilter(item)) {
						return true;
					}
				}
				return false;
			}
			return true;
		}

	}

	private class FindFilter extends PatternFilter {

		private boolean filterMatches(String text) {
			return text != null && wordMatches(text);
		}

		@Override
		protected Object[] getChildren(Object element) {
			if (element instanceof CatalogCategory) {
				return ((CatalogCategory) element).getItems().toArray();
			}
			return super.getChildren(element);
		}

		@Override
		protected boolean isLeafMatch(Viewer viewer, Object element) {
			if (element instanceof CatalogItem) {
				CatalogItem descriptor = (CatalogItem) element;
				if (!(filterMatches(descriptor.getName()) || filterMatches(descriptor.getDescription())
						|| filterMatches(descriptor.getProvider()) || filterMatches(descriptor.getLicense()))) {
					return false;
				}
				return true;
			}
			return false;
		}

	}

//	private class ConnectorBorderPaintListener implements PaintListener {
//		public void paintControl(PaintEvent e) {
//			Composite composite = (Composite) e.widget;
//			Rectangle bounds = composite.getBounds();
//			GC gc = e.gc;
//			gc.setLineStyle(SWT.LINE_DOT);
//			gc.drawLine(bounds.x, bounds.y, bounds.x + bounds.width, bounds.y);
//		}
//	}

	private static final int DEFAULT_HEIGHT = 250;

	private final Catalog catalog;

	private final List<CatalogItem> checkedItems = new ArrayList<CatalogItem>();

	private boolean complete;

	private final CatalogConfiguration configuration;

	private final IRunnableContext context;

	private boolean ignoreUpdates;

	private Set<String> installedFeatures;

	private DiscoveryResources resources;

	private final SelectionProviderAdapter selectionProvider;

	private final IShellProvider shellProvider;

	boolean showInstalled;

	private Button showInstalledCheckbox;

	private Set<Tag> visibleTags;

	public CatalogViewer(Catalog catalog, IShellProvider shellProvider, IRunnableContext context,
			CatalogConfiguration configuration) {
		Assert.isNotNull(catalog);
		Assert.isNotNull(shellProvider);
		Assert.isNotNull(context);
		Assert.isNotNull(configuration);
		this.catalog = catalog;
		this.shellProvider = shellProvider;
		this.context = context;
		this.configuration = configuration;
		this.selectionProvider = new SelectionProviderAdapter();
		this.showInstalled = configuration.isShowInstalled();
		if (configuration.getSelectedTags() != null) {
			this.visibleTags = new HashSet<Tag>(configuration.getSelectedTags());
		} else {
			this.visibleTags = new HashSet<Tag>();
		}
		setMinimumHeight(DEFAULT_HEIGHT);
		setComplete(false);
	}

	public void addSelectionChangedListener(ISelectionChangedListener listener) {
		selectionProvider.addSelectionChangedListener(listener);
	}

	protected void catalogUpdated(boolean wasCancelled) {
		if (catalog != null && !wasCancelled) {
			int categoryWithConnectorCount = 0;
			for (CatalogCategory category : catalog.getCategories()) {
				categoryWithConnectorCount += category.getItems().size();
			}
			if (categoryWithConnectorCount == 0) {
				// nothing was discovered: notify the user
				MessageDialog.openWarning(getShell(), Messages.ConnectorDiscoveryWizardMainPage_noConnectorsFound,
						Messages.ConnectorDiscoveryWizardMainPage_noConnectorsFound_description);
			}
		}
		viewer.setInput(catalog);
		selectionProvider.setSelection(StructuredSelection.EMPTY);
	}

	private IStatus computeStatus(InvocationTargetException e, String message) {
		Throwable cause = e.getCause();
		IStatus statusCause;
		if (cause instanceof CoreException) {
			statusCause = ((CoreException) cause).getStatus();
		} else {
			statusCause = new Status(IStatus.ERROR, DiscoveryUi.ID_PLUGIN, cause.getMessage(), cause);
		}
		if (statusCause.getMessage() != null) {
			message = NLS.bind(Messages.ConnectorDiscoveryWizardMainPage_message_with_cause, message,
					statusCause.getMessage());
		}
		IStatus status = new MultiStatus(DiscoveryUi.ID_PLUGIN, 0, new IStatus[] { statusCause }, message, cause);
		return status;
	}

	protected Pattern createPattern(String filterText) {
		if (filterText == null || filterText.length() == 0) {
			return null;
		}
		String regex = filterText;
		regex.replace("\\", "\\\\").replace("?", ".").replace("*", ".*?"); //$NON-NLS-1$//$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
		return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
	}

	public void dispose() {
		if (catalog != null) {
			catalog.dispose();
		}
	}

	@Override
	protected PatternFilter doCreateFilter() {
		return new FindFilter();
	}

	@Override
	protected void doCreateHeaderControls(Composite parent) {
		if (configuration.isShowInstalledFilter()) {
			showInstalledCheckbox = new Button(parent, SWT.CHECK);
			showInstalledCheckbox.setSelection(showInstalled);
			showInstalledCheckbox.setText(Messages.DiscoveryViewer_Show_Installed);
			showInstalledCheckbox.addSelectionListener(new SelectionListener() {

				public void widgetDefaultSelected(SelectionEvent e) {
					widgetSelected(e);
				}

				public void widgetSelected(SelectionEvent e) {
					if (ignoreUpdates) {
						return;
					}

					ignoreUpdates = true;
					setShowInstalled(showInstalledCheckbox.getSelection());
					ignoreUpdates = false;
				}
			});
		}
		if (configuration.isShowTagFilter()) {
			for (final Tag tag : catalog.getTags()) {
				final Button checkbox = new Button(parent, SWT.CHECK);
				checkbox.setSelection(visibleTags.contains(tag));
				checkbox.setText(tag.getLabel());
				checkbox.addSelectionListener(new SelectionListener() {
					public void widgetDefaultSelected(SelectionEvent e) {
						widgetSelected(e);
					}

					public void widgetSelected(SelectionEvent e) {
						boolean selection = checkbox.getSelection();
						if (selection) {
							visibleTags.add(tag);
						} else {
							visibleTags.remove(tag);
						}
						refresh();
					}
				});
			}
		}
	}

	@Override
	protected StructuredViewer doCreateViewer(Composite container) {
		StructuredViewer viewer = new ControlListViewer(container, SWT.BORDER) {
			@Override
			protected ControlListItem<?> doCreateItem(Composite parent, Object element) {
				return doCreateViewerItem(parent, element);
			}
		};
		viewer.setContentProvider(new ITreeContentProvider() {

			private Catalog catalog;

			public void dispose() {
				catalog = null;
			}

			public Object[] getChildren(Object parentElement) {
				if (parentElement instanceof CatalogCategory) {
					return ((CatalogCategory) parentElement).getItems().toArray();
				}
				return null;
			}

			public Object[] getElements(Object inputElement) {
				if (catalog != null) {
					List<Object> elements = new ArrayList<Object>();
					elements.addAll(catalog.getCategories());
					elements.addAll(catalog.getItems());
					return elements.toArray(new Object[0]);
				}
				return new Object[0];
			}

			public Object getParent(Object element) {
				if (element instanceof CatalogCategory) {
					return catalog;
				}
				if (element instanceof CatalogItem) {
					return ((CatalogItem) element).getCategory();
				}
				return null;
			}

			public boolean hasChildren(Object element) {
				if (element instanceof CatalogCategory) {
					return ((CatalogCategory) element).getItems().size() > 0;
				}
				return false;
			}

			public void inputChanged(Viewer viewer, Object oldInput, Object newInput) {
				this.catalog = (Catalog) newInput;
			}
		});
		viewer.setSorter(new ViewerSorter() {
			CatalogCategoryComparator categoryComparator = new CatalogCategoryComparator();

			CatalogItemComparator itemComparator = new CatalogItemComparator();

			@Override
			public int compare(Viewer viewer, Object o1, Object o2) {
				CatalogCategory cat1 = getCategory(o1);
				CatalogCategory cat2 = getCategory(o2);

				// FIXME filter uncategorized items?
				if (cat1 == null) {
					return (cat2 != null) ? 1 : 0;
				} else if (cat2 == null) {
					return 1;
				}

				int i = categoryComparator.compare(cat1, cat2);
				if (i == 0) {
					if (o1 instanceof CatalogCategory) {
						return -1;
					}
					if (o2 instanceof CatalogCategory) {
						return 1;
					}
					if (cat1 == cat2 && o1 instanceof CatalogItem && o2 instanceof CatalogItem) {
						return itemComparator.compare((CatalogItem) o1, (CatalogItem) o2);
					}
					return super.compare(viewer, o1, o2);
				}
				return i;
			}

//					private int compare(Comparator<Object> comparator, Object key1, Object key2) {
//						if (key1 == null) {
//							return (key2 != null) ? 1 : 0;
//						} else if (key2 == null) {
//							return -1;
//						}
//						return comparator.compare(key1, key2);
//					}
			private CatalogCategory getCategory(Object o) {
				if (o instanceof CatalogCategory) {
					return (CatalogCategory) o;
				}
				if (o instanceof CatalogItem) {
					return ((CatalogItem) o).getCategory();
				}
				return null;
			}
		});

		resources = new DiscoveryResources(container.getDisplay());
		viewer.getControl().addDisposeListener(new DisposeListener() {
			public void widgetDisposed(DisposeEvent e) {
				resources.dispose();
			}
		});
		viewer.addFilter(new Filter());
		return viewer;
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	protected ControlListItem<?> doCreateViewerItem(Composite parent, Object element) {
		if (element instanceof CatalogItem) {
			return new DiscoveryItem(parent, SWT.NONE, resources, shellProvider, (CatalogItem) element, this);
		} else if (element instanceof CatalogCategory) {
			return new CategoryItem(parent, SWT.NONE, resources, (CatalogCategory) element);
		}
		return null;
	}

	protected boolean doFilter(CatalogItem item) {
		if (!showInstalled && item.isInstalled()) {
			return false;
		}

		if (!isTagVisible(item)) {
			return false;
		}

		for (CatalogFilter filter : configuration.getFilters()) {
			if (!filter.select(item)) {
				return false;
			}
		}

		return true;
	}

	public Catalog getCatalog() {
		return catalog;
	}

	public List<CatalogItem> getCheckedItems() {
		return new ArrayList<CatalogItem>(checkedItems);
	}

	public CatalogConfiguration getConfiguration() {
		return configuration;
	}

	protected Set<String> getInstalledFeatures(IProgressMonitor monitor) throws InterruptedException {
		Set<String> installedFeatures = new HashSet<String>();
		IBundleGroupProvider[] bundleGroupProviders = Platform.getBundleGroupProviders();
		for (IBundleGroupProvider provider : bundleGroupProviders) {
			if (monitor.isCanceled()) {
				throw new InterruptedException();
			}
			IBundleGroup[] bundleGroups = provider.getBundleGroups();
			for (IBundleGroup group : bundleGroups) {
				installedFeatures.add(group.getIdentifier());
			}
		}
		return installedFeatures;
	}

	public IStructuredSelection getSelection() {
		return (IStructuredSelection) selectionProvider.getSelection();
	}

	private Shell getShell() {
		return shellProvider.getShell();
	}

	public boolean isComplete() {
		return complete;
	}

	public boolean isShowInstalled() {
		return showInstalled;
	}

	private boolean isTagVisible(CatalogItem item) {
		if (!configuration.isShowTagFilter()) {
			return true;
		}
		for (Tag selectedTag : visibleTags) {
			for (Tag tag : item.getTags()) {
				if (tag.equals(selectedTag)) {
					return true;
				}
			}
		}
		return false;
	}

	void modifySelection(final CatalogItem connector, boolean selected) {
		modifySelectionInternal(connector, selected);
		updateState();
	}

	private void modifySelectionInternal(final CatalogItem connector, boolean selected) {
		connector.setSelected(selected);
		if (selected) {
			checkedItems.add(connector);
		} else {
			checkedItems.remove(connector);
		}
	}

	protected void postDiscovery() {
		for (CatalogItem connector : catalog.getItems()) {
			connector.setInstalled(installedFeatures != null
					&& installedFeatures.containsAll(connector.getInstallableUnits()));
		}
	}

	public void refresh() {
		if (viewer != null && !viewer.getControl().isDisposed()) {
			viewer.refresh();
		}
	}

	public void removeSelectionChangedListener(ISelectionChangedListener listener) {
		selectionProvider.removeSelectionChangedListener(listener);
	}

	public void setComplete(boolean complete) {
		this.complete = complete;
	}

	public void setSelection(IStructuredSelection selection) {
		Set<CatalogItem> selected = new HashSet<CatalogItem>();
		for (Object descriptor : selection.toArray()) {
			if (descriptor instanceof CatalogItem) {
				selected.add((CatalogItem) descriptor);
			}
		}
		for (CatalogItem connector : catalog.getItems()) {
			modifySelectionInternal(connector, selected.contains(connector));
		}
		updateState();
	}

	public void setShowInstalled(boolean showInstalled) {
		this.showInstalled = showInstalled;
		showInstalledCheckbox.setSelection(showInstalled);
		refresh();
	}

	public void updateCatalog() {
		boolean wasCancelled = false;
		try {
			final IStatus[] result = new IStatus[1];
			context.run(true, true, new IRunnableWithProgress() {
				public void run(IProgressMonitor monitor) throws InvocationTargetException, InterruptedException {
					if (installedFeatures == null) {
						installedFeatures = getInstalledFeatures(monitor);
					}

					result[0] = catalog.performDiscovery(monitor);
					if (monitor.isCanceled()) {
						throw new InterruptedException();
					}

					postDiscovery();
				}
			});

			if (result[0] != null && !result[0].isOK()) {
				StatusManager.getManager().handle(result[0],
						StatusManager.SHOW | StatusManager.BLOCK | StatusManager.LOG);
			}
		} catch (InvocationTargetException e) {
			IStatus status = computeStatus(e, Messages.ConnectorDiscoveryWizardMainPage_unexpectedException);
			StatusManager.getManager().handle(status, StatusManager.SHOW | StatusManager.BLOCK | StatusManager.LOG);
		} catch (InterruptedException e) {
			// cancelled by user so nothing to do here.
			wasCancelled = true;
		}
		if (catalog != null) {
			catalogUpdated(wasCancelled);
			if (configuration.isVerifyUpdateSiteAvailability() && !catalog.getItems().isEmpty()) {
				try {
					context.run(true, true, new IRunnableWithProgress() {
						public void run(IProgressMonitor monitor) throws InvocationTargetException,
								InterruptedException {
							//discovery.verifySiteAvailability(monitor);
						}
					});
				} catch (InvocationTargetException e) {
					IStatus status = computeStatus(e, Messages.ConnectorDiscoveryWizardMainPage_unexpectedException);
					StatusManager.getManager().handle(status,
							StatusManager.SHOW | StatusManager.BLOCK | StatusManager.LOG);
				} catch (InterruptedException e) {
					// cancelled by user so nothing to do here.
					wasCancelled = true;
				}
			}
		}
		// help UI tests
		viewer.setData("discoveryComplete", "true"); //$NON-NLS-1$//$NON-NLS-2$
	}

	private void updateState() {
		setComplete(!checkedItems.isEmpty());
		selectionProvider.setSelection(new StructuredSelection(getCheckedItems()));
	}
}