/*******************************************************************************
 * Copyright (c) 2007, 2008 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.equinox.internal.p2.reconciler.dropins;

import org.eclipse.equinox.internal.p2.update.Site;
import org.eclipse.equinox.internal.provisional.p2.metadata.repository.IMetadataRepository;

/*
 * Internal class contains information about watchers and sites. Used for caching.
 */
public class SiteInfo {
	private IMetadataRepository repository;
	private Site site;
	private String url;

	public SiteInfo(Site site, IMetadataRepository repository) {
		super();
		this.site = site;
		this.url = site.getUrl();
		this.repository = repository;
	}

	public Site getSite() {
		return site;
	}

	public String getUrl() {
		return url;
	}

	public IMetadataRepository getRepository() {
		return repository;
	}

	/* (non-Javadoc)
	 * @see java.lang.Object#hashCode()
	 */
	public int hashCode() {
		return url.hashCode();
	}

	/* (non-Javadoc)
	 * @see java.lang.Object#equals(java.lang.Object)
	 */
	public boolean equals(Object obj) {
		if (!(obj instanceof SiteInfo))
			return false;
		SiteInfo other = (SiteInfo) obj;
		// this is ok because they are strings and not real URLs
		return url.equals(other.getUrl());
	}
}
