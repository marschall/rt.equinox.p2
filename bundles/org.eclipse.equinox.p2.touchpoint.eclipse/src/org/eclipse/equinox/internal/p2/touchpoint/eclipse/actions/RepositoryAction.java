/*******************************************************************************
 *  Copyright (c) 2008, 2009 IBM Corporation and others.
 *  All rights reserved. This program and the accompanying materials
 *  are made available under the terms of the Eclipse Public License v1.0
 *  which accompanies this distribution, and is available at
 *  http://www.eclipse.org/legal/epl-v10.html
 * 
 *  Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.equinox.internal.p2.touchpoint.eclipse.actions;

import org.eclipse.equinox.internal.provisional.p2.engine.EngineSession;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.equinox.internal.p2.core.helpers.ServiceHelper;
import org.eclipse.equinox.internal.p2.engine.Profile;
import org.eclipse.equinox.internal.p2.touchpoint.eclipse.Activator;
import org.eclipse.equinox.internal.p2.touchpoint.eclipse.Util;
import org.eclipse.equinox.internal.provisional.p2.artifact.repository.IArtifactRepositoryManager;
import org.eclipse.equinox.internal.provisional.p2.engine.*;
import org.eclipse.equinox.internal.provisional.p2.metadata.repository.IMetadataRepositoryManager;
import org.eclipse.equinox.internal.provisional.p2.repository.*;
import org.eclipse.equinox.p2.core.IAgentLocation;
import org.eclipse.osgi.util.NLS;
import org.osgi.service.prefs.BackingStoreException;
import org.osgi.service.prefs.Preferences;

/**
 * Helper base class for dealing with repositories associated with profiles. Repositories
 * are associated with a profile by encoding the repository locations in a comma-delimited
 * list in a profile property.
 * @see AddRepositoryAction
 * @see RemoveRepositoryAction
 */
abstract class RepositoryAction extends ProvisioningAction {

	private static final String METADATA_REPOSITORY = "org.eclipse.equinox.p2.metadata.repository"; //$NON-NLS-1$
	private static final String ARTIFACT_REPOSITORY = "org.eclipse.equinox.p2.artifact.repository"; //$NON-NLS-1$

	private static final String NODE_REPOSITORIES = "repositories"; //$NON-NLS-1$
	private static final String REPOSITORY_COUNT = "count"; //$NON-NLS-1$
	private static final String KEY_URI = "uri"; //$NON-NLS-1$
	private static final String KEY_ENABLED = "enabled"; //$NON-NLS-1$
	private static final String KEY_NICKNAME = "nickname"; //$NON-NLS-1$

	/**
	 * Returns the repository manager of the given type, or <code>null</code>
	 * if not available.
	 */
	private static IRepositoryManager getRepositoryManager(int type) {
		if (type == IRepository.TYPE_METADATA) {
			return (IRepositoryManager) ServiceHelper.getService(Activator.getContext(), IMetadataRepositoryManager.SERVICE_NAME);
		} else if (type == IRepository.TYPE_ARTIFACT) {
			return (IRepositoryManager) ServiceHelper.getService(Activator.getContext(), IArtifactRepositoryManager.SERVICE_NAME);
		}
		return null;
	}

	/**
	 * Associates the repository described by the given event with the given profile.
	 * Has no effect if the repository is already associated with this profile.
	 */
	protected void addRepositoryToProfile(IAgentLocation agentLocation, Profile profile, URI location, String nickname, int type, boolean enabled) {
		Preferences node = getRepositoryPreferenceNode(agentLocation, profile, location, type);
		int count = 0;

		if (repositoryExists(node)) {
			count = getRepositoryCount(node);
			// If a user has added a repository we need to set the initial count manually
			if (count == 0)
				count = 1;
		}
		node.put(KEY_URI, location.toString());
		node.put(KEY_ENABLED, Boolean.toString(enabled));
		if (nickname != null)
			node.put(KEY_NICKNAME, nickname);
		count++;
		setRepositoryCount(node, count);
		try {
			node.flush();
		} catch (BackingStoreException e) {
			// TODO: perhaps an Exception should be passed backwards and associated with State
		}
	}

	/**
	 * Adds the repository corresponding to the given event to the currently running instance.
	 */
	protected void addToSelf(IAgentLocation agentLocation, RepositoryEvent event) {
		IRepositoryManager manager = getRepositoryManager(event.getRepositoryType());
		final URI location = event.getRepositoryLocation();
		Preferences node = getRepositoryPreferenceNode(agentLocation, null, location, event.getRepositoryType());

		int count = getRepositoryCount(node);
		if (manager.contains(location)) {
			// If a user as added a repository we need to set the initial count manually
			if (count == 0)
				count = 1;
		} else {
			if (manager != null)
				manager.addRepository(location);
		}
		// increment the counter & send to preferences
		count++;
		setRepositoryCount(node, count);

		if (!event.isRepositoryEnabled())
			manager.setEnabled(location, false);
		final String name = event.getRepositoryNickname();
		if (name != null)
			manager.setRepositoryProperty(location, IRepository.PROP_NICKNAME, name);
	}

	protected RepositoryEvent createEvent(Map parameters) throws CoreException {
		String parm = (String) parameters.get(ActionConstants.PARM_REPOSITORY_LOCATION);
		if (parm == null)
			throw new CoreException(Util.createError(NLS.bind(Messages.parameter_not_set, ActionConstants.PARM_REPOSITORY_LOCATION, getId())));
		URI location = null;
		try {
			location = new URI(parm);
		} catch (URISyntaxException e) {
			throw new CoreException(Util.createError(NLS.bind(Messages.parameter_not_set, ActionConstants.PARM_REPOSITORY_LOCATION, getId()), e));
		}
		parm = (String) parameters.get(ActionConstants.PARM_REPOSITORY_TYPE);
		if (parm == null)
			throw new CoreException(Util.createError(NLS.bind(Messages.parameter_not_set, ActionConstants.PARM_REPOSITORY_TYPE, getId())));
		int type = 0;
		try {
			type = Integer.parseInt(parm);
		} catch (NumberFormatException e) {
			throw new CoreException(Util.createError(NLS.bind(Messages.parameter_not_set, ActionConstants.PARM_REPOSITORY_TYPE, getId()), e));
		}
		String name = (String) parameters.get(ActionConstants.PARM_REPOSITORY_NICKNAME);
		//default is to be enabled
		String enablement = (String) parameters.get(ActionConstants.PARM_REPOSITORY_ENABLEMENT);
		boolean enabled = enablement == null ? true : Boolean.valueOf(enablement).booleanValue();
		return RepositoryEvent.newDiscoveryEvent(location, name, type, enabled);
	}

	/**
	 * Returns the id of this action.
	 */
	protected abstract String getId();

	/**
	 * Return <code>true</code> if the given profile is the currently running profile,
	 * and <code>false</code> otherwise.
	 */
	protected boolean isSelfProfile(IProfileRegistry registry, Profile profile) {
		//if we can't determine the current profile, assume we are running on self
		if (profile == null)
			return true;
		if (registry == null)
			return false;
		final IProfile selfProfile = registry.getProfile(IProfileRegistry.SELF);
		//if we can't determine the self profile, assume we are running on self
		if (selfProfile == null)
			return true;
		return profile.getProfileId().equals(selfProfile.getProfileId());
	}

	/**
	 * Removes the repository corresponding to the given event from the currently running instance.
	 */
	protected void removeFromSelf(IAgentLocation agentLocation, RepositoryEvent event) {
		IRepositoryManager manager = getRepositoryManager(event.getRepositoryType());
		Preferences node = getRepositoryPreferenceNode(agentLocation, null, event.getRepositoryLocation(), event.getRepositoryType());
		int count = getRepositoryCount(node);
		if (--count < 1 && manager != null)
			manager.removeRepository(event.getRepositoryLocation());
		setRepositoryCount(node, count);
	}

	/**
	 * Removes the association between the repository described by the given event
	 * and the given profile. Has no effect if the location is not already associated with
	 * this profile.
	 */
	protected void removeRepositoryFromProfile(IAgentLocation agentLocation, Profile profile, URI location, int type) {
		Preferences node = getRepositoryPreferenceNode(agentLocation, profile, location, type);

		int count = getRepositoryCount(node);
		if (--count < 1) {
			// TODO: Remove all associated values
			try {
				String[] keys = node.keys();

				for (int i = 0; i < keys.length; i++)
					node.remove(keys[i]);
			} catch (BackingStoreException e) {
				// TODO: Should this be passed back to be associated with State?
			}

		} else
			setRepositoryCount(node, count);

		try {
			node.flush();
		} catch (BackingStoreException e) {
			// TODO: perhaps an Exception should be passed backwards and associated with State
		}
	}

	/*
	 * Get the counter associated with a repository 
	 */
	protected int getRepositoryCount(Preferences node) {
		return node.getInt(REPOSITORY_COUNT, 0);
	}

	/*
	 * Sets the counter associated with this repository to a specific value
	 */
	protected void setRepositoryCount(Preferences node, int count) {
		if (count < 1)
			node.remove(REPOSITORY_COUNT);
		else
			node.putInt(REPOSITORY_COUNT, count);
	}

	/*
	 * Determine if a repository is already known
	 */
	protected boolean repositoryExists(Preferences node) {
		if (node.get(KEY_URI, null) == null)
			return false;
		return true;
	}

	/*
	 * Get the preference node associated with profile & location 
	 */
	protected Preferences getRepositoryPreferenceNode(IAgentLocation agentLocation, Profile profile, URI location, int type) {
		String key = type == IRepository.TYPE_METADATA ? METADATA_REPOSITORY : ARTIFACT_REPOSITORY;
		String profileId = profile == null ? IProfileRegistry.SELF : profile.getProfileId();
		return new ProfileScope(agentLocation, profileId).getNode(key + '/' + NODE_REPOSITORIES + '/' + getKey(location));
	}

	/*
	 * Copied from AbstractRepositoryManager
	 */
	private String getKey(URI location) {
		String key = location.toString().replace('/', '_');
		//remove trailing slash
		if (key.endsWith("_")) //$NON-NLS-1$
			key = key.substring(0, key.length() - 1);
		return key;
	}

	protected EngineSession getSession(Map parameters) throws CoreException {
		//We shouldn't really know about the session parameter
		EngineSession session = (EngineSession) parameters.get("session"); //$NON-NLS-1$
		if (session == null)
			throw new CoreException(Util.createError(NLS.bind(Messages.parameter_not_set, "session", getId()))); //$NON-NLS-1$
		return session;
	}
}
