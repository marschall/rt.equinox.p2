/*******************************************************************************
 * Copyright (c) 2007, 2009 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.equinox.internal.provisional.p2.ui;

import java.net.URI;
import java.util.*;
import org.eclipse.core.runtime.*;
import org.eclipse.equinox.internal.p2.ui.ProvUIActivator;
import org.eclipse.equinox.internal.p2.ui.ProvUIMessages;
import org.eclipse.equinox.internal.provisional.p2.core.ProvisionException;
import org.eclipse.equinox.internal.provisional.p2.core.repository.IRepository;
import org.eclipse.equinox.internal.provisional.p2.core.repository.IRepositoryManager;
import org.eclipse.equinox.internal.provisional.p2.query.*;
import org.eclipse.equinox.internal.provisional.p2.ui.policy.IUViewQueryContext;
import org.eclipse.osgi.util.NLS;
import org.eclipse.ui.statushandlers.StatusManager;

/**
 * An object that provides specialized repository query support in place of
 * a repository manager and its repositories.  The repositories to be included 
 * can be specified using the repository flags defined in the UI policy.  The query
 * itself is run on the the repositories themselves, if supported by the particular
 * kind of repository.  If the repository doesn't support queryies, or the query is
 * a {@link RepositoryLocationQuery}, the query is run over
 * the repository locations instead.  
 */
public abstract class QueryableRepositoryManager implements IQueryable {
	/**
	 * List<URI> of locations of repositories that were not found
	 */
	private ArrayList notFound = new ArrayList();

	/**
	 * Map<URI,IRepository> of loaded repositories.
	 */
	private HashMap loaded = new HashMap();

	private MultiStatus accumulatedNotFound = null;
	private boolean includeDisabledRepos;
	private IUViewQueryContext queryContext;

	public QueryableRepositoryManager(IUViewQueryContext queryContext, boolean includeDisabledRepos) {
		this.includeDisabledRepos = includeDisabledRepos;
		Assert.isNotNull(queryContext);
		this.queryContext = queryContext;
	}

	/**
	 * Iterates over the repositories configured in this queryable.
	 * For most queries, the query is run on each repository, passing any objects that satisfy the
	 * query to the provided collector.  If the query is a {@link RepositoryLocationQuery}, the query
	 * is run on the repository locations instead.
	 * <p>
	 * This method is long-running; progress and cancellation are provided
	 * by the given progress monitor. 
	 * </p>
	 * 
	 * @param query The query to perform..
	 * @param result Collects the results of the query, run on either the repository URIs, or on
	 *    the repositories themselves.
	 * @param monitor a progress monitor, or <code>null</code> if progress
	 *    reporting is not desired
	 * @return The collector argument
	 */
	public Collector query(Query query, Collector result, IProgressMonitor monitor) {
		IRepositoryManager manager = getRepositoryManager();
		if (manager == null) {
			ProvUI.reportStatus(new Status(IStatus.ERROR, ProvUIActivator.PLUGIN_ID, ProvUIMessages.ProvisioningUtil_NoRepositoryManager), StatusManager.SHOW | StatusManager.LOG);
			return result;
		}
		if (monitor == null)
			monitor = new NullProgressMonitor();
		query(getRepoLocations(manager), query, result, monitor);
		reportAccumulatedStatus();
		return result;
	}

	/**
	 * Load all of the repositories referenced by this queryable.  This is an expensive operation.
	 * The status of any not found repositories is accumulated and must be reported manually
	 * using reportAccumulatedStatus()
	 * 
	 * @param monitor the progress monitor that should be used
	 */
	public void loadAll(IProgressMonitor monitor) {
		IRepositoryManager manager = getRepositoryManager();
		if (manager == null) {
			ProvUI.reportStatus(new Status(IStatus.ERROR, ProvUIActivator.PLUGIN_ID, ProvUIMessages.ProvisioningUtil_NoRepositoryManager), StatusManager.SHOW | StatusManager.LOG);
			return;
		}
		URI[] repoLocations = getRepoLocations(manager);
		SubMonitor sub = SubMonitor.convert(monitor, repoLocations.length * 100);
		if (sub.isCanceled())
			return;
		for (int i = 0; i < repoLocations.length; i++) {
			if (sub.isCanceled())
				return;
			try {
				loadRepository(manager, repoLocations[i], sub.newChild(100));
			} catch (ProvisionException e) {
				if (e.getStatus().getCode() == ProvisionException.REPOSITORY_NOT_FOUND)
					handleNotFound(e, repoLocations[i]);
				else
					ProvUI.handleException(e, NLS.bind(ProvUIMessages.ProvisioningUtil_LoadRepositoryFailure, repoLocations[i]), StatusManager.LOG);
			}
		}
	}

	/**
	 * Returns an array of repository locations.
	 */
	private URI[] getRepoLocations(IRepositoryManager manager) {
		Set locations = new HashSet();
		int flags = queryContext.getMetadataRepositoryFlags();
		locations.addAll(Arrays.asList(manager.getKnownRepositories(flags)));
		if (includeDisabledRepos) {
			locations.addAll(Arrays.asList(manager.getKnownRepositories(IRepositoryManager.REPOSITORIES_DISABLED | flags)));
		}
		return (URI[]) locations.toArray(new URI[locations.size()]);
	}

	protected void handleNotFound(ProvisionException e, URI missingRepo) {
		// If we thought we had loaded it, get rid of the reference
		loaded.remove(missingRepo);
		// If we've already reported a URL is not found, don't report again.
		if (notFound.contains(missingRepo))
			return;
		// If someone else reported a URL is not found, don't report again.
		if (ProvUI.hasNotFoundStatusBeenReported(missingRepo)) {
			notFound.add(missingRepo);
			return;
		}
		notFound.add(missingRepo);
		ProvUI.notFoundStatusReported(missingRepo);
		// Empty multi statuses have a severity OK.  The platform status handler doesn't handle
		// this well.  We correct this by recreating a status with error severity
		// so that the platform status handler does the right thing.
		IStatus status = e.getStatus();
		if (status instanceof MultiStatus && ((MultiStatus) status).getChildren().length == 0)
			status = new Status(IStatus.ERROR, status.getPlugin(), status.getCode(), status.getMessage(), status.getException());
		if (accumulatedNotFound == null) {
			accumulatedNotFound = new MultiStatus(ProvUIActivator.PLUGIN_ID, ProvisionException.REPOSITORY_NOT_FOUND, new IStatus[] {status}, ProvUIMessages.QueryableMetadataRepositoryManager_MultipleRepositoriesNotFound, null);
		} else {
			accumulatedNotFound.add(status);
		}
	}

	public void reportAccumulatedStatus() {
		// If we've discovered not found repos we didn't know about, report them
		if (accumulatedNotFound != null) {
			// If there is only missing repo to report, use the specific message rather than the generic.
			if (accumulatedNotFound.getChildren().length == 1) {
				ProvUI.reportStatus(accumulatedNotFound.getChildren()[0], StatusManager.SHOW);
			} else {
				ProvUI.reportStatus(accumulatedNotFound, StatusManager.SHOW);
			}
		}
		// Reset the accumulated status so that next time we only report the newly not found repos.
		accumulatedNotFound = null;
	}

	/**
	 * Return a boolean indicating whether the repositories to be queried
	 * are already loaded.
	 * 
	 * @return <code>true</code> if all repositories to be queried by the
	 * receiver are loaded, <code>false</code> if they
	 * are not.
	 */
	public boolean areRepositoriesLoaded() {
		IRepositoryManager mgr = getRepositoryManager();
		if (mgr == null)
			return false;
		URI[] repoURIs = getRepoLocations(mgr);
		for (int i = 0; i < repoURIs.length; i++) {
			IRepository repo = getRepository(mgr, repoURIs[i]);
			if (repo == null)
				return false;
		}
		return true;
	}

	protected IRepository loadRepository(IRepositoryManager manager, URI location, IProgressMonitor monitor) throws ProvisionException {
		Object repo = loaded.get(location);
		if (repo == null) {
			monitor.setTaskName(NLS.bind(ProvUIMessages.QueryableMetadataRepositoryManager_LoadRepositoryProgress, URIUtil.toUnencodedString(location)));
			repo = doLoadRepository(manager, location, monitor);
			if (repo != null)
				loaded.put(location, repo);
		} else {
			monitor.done();
		}
		return (IRepository) repo;
	}

	/**
	 * Return the appropriate repository manager, or <code>null</code> if none could be found.
	 * @return the repository manager
	 */
	protected abstract IRepositoryManager getRepositoryManager();

	/**
	 * Get an already-loaded repository at the specified location.
	 * 
	 * @param manager the manager
	 * @param location the repository location
	 * @return the repository at that location, or <code>null</code> if no repository is
	 * yet located at that location.
	 */
	protected IRepository getRepository(IRepositoryManager manager, URI location) {
		return (IRepository) loaded.get(location);
	}

	/**
	 * Load the repository located at the specified location.
	 * 
	 * @param manager the manager
	 * @param location the repository location
	 * @param monitor the progress monitor
	 * @return the repository that was loaded, or <code>null</code> if no repository could
	 * be found at that location.
	 */
	protected abstract IRepository doLoadRepository(IRepositoryManager manager, URI location, IProgressMonitor monitor) throws ProvisionException;

	protected abstract Collector query(URI[] uris, Query query, Collector collector, IProgressMonitor monitor);

	public void setQueryContext(IUViewQueryContext queryContext) {
		this.queryContext = queryContext;
	}

}
