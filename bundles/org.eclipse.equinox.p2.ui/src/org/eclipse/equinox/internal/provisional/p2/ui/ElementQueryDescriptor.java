/*******************************************************************************
 * Copyright (c) 2007, 2009 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *     EclipseSource - ongoing development
 *******************************************************************************/
package org.eclipse.equinox.internal.provisional.p2.ui;

import java.util.Collection;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.equinox.internal.provisional.p2.query.*;

/**
 * ElementQueryDescriptor represents everything needed to run a query, including
 * the object to be queried, the query to use, and the query result.  It can optionally
 * wrap the query results in a UI element.
 * 
 * @since 3.4
 */
public class ElementQueryDescriptor {

	private Query query;
	private Collector collector;
	private IQueryable queryable;
	private ElementWrapper wrapper;

	/**
	 * Creates an ElementQueryDescriptor to represent a Query, its collector the queryable
	 * on which it will run.
	 */
	public ElementQueryDescriptor(IQueryable queryable, Query query, Collector collector) {
		this(queryable, query, collector, null);
	}

	/**
	 * Creates an ElementQueryDescriptor to represent a Query, its collector the queryable
	 * on which it will run, and the transformer used to transform the results.
	 */
	public ElementQueryDescriptor(IQueryable queryable, Query query, Collector collector, ElementWrapper wrapper) {
		this.query = query;
		this.collector = collector;
		this.queryable = queryable;
		this.wrapper = wrapper;
	}

	public boolean isComplete() {
		return query != null && collector != null && queryable != null;
	}

	/**
	 * Performs the query returning a collection of results.
	 * @param monitor
	 */
	public Collection performQuery(IProgressMonitor monitor) {
		Collector results = this.queryable.query(this.query, this.collector, monitor);
		if (wrapper != null)
			return wrapper.getElements(results);
		return results.toCollection();
	}

	public boolean hasCollector() {
		return this.collector != null;
	}

	public boolean hasQueryable() {
		return this.queryable != null;
	}

	public boolean hasQuery() {
		return this.query != null;
	}
}
