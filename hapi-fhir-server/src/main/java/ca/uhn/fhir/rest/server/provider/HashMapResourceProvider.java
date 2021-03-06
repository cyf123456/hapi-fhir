package ca.uhn.fhir.rest.server.provider;

/*-
 * #%L
 * HAPI FHIR - Server Framework
 * %%
 * Copyright (C) 2014 - 2018 University Health Network
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.annotation.*;
import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.param.TokenAndListParam;
import ca.uhn.fhir.rest.param.TokenOrListParam;
import ca.uhn.fhir.rest.param.TokenParam;
import ca.uhn.fhir.rest.server.IResourceProvider;
import ca.uhn.fhir.rest.server.exceptions.ResourceGoneException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * This class is a simple implementation of the resource provider
 * interface that uses a HashMap to store all resources in memory.
 * <p>
 * This class currently supports the following FHIR operations:
 * </p>
 * <ul>
 * <li>Create</li>
 * <li>Update existing resource</li>
 * <li>Update non-existing resource (e.g. create with client-supplied ID)</li>
 * <li>Delete</li>
 * <li>Search by resource type with no parameters</li>
 * </ul>
 *
 * @param <T> The resource type to support
 */
public class HashMapResourceProvider<T extends IBaseResource> implements IResourceProvider {
	private static final Logger ourLog = LoggerFactory.getLogger(HashMapResourceProvider.class);
	private final Class<T> myResourceType;
	private final FhirContext myFhirContext;
	private final String myResourceName;
	protected Map<String, TreeMap<Long, T>> myIdToVersionToResourceMap = new HashMap<>();
	private long myNextId;
	private AtomicLong myDeleteCount = new AtomicLong(0);
	private AtomicLong mySearchCount = new AtomicLong(0);
	private AtomicLong myUpdateCount = new AtomicLong(0);
	private AtomicLong myCreateCount = new AtomicLong(0);
	private AtomicLong myReadCount = new AtomicLong(0);

	/**
	 * Constructor
	 *
	 * @param theFhirContext  The FHIR context
	 * @param theResourceType The resource type to support
	 */
	@SuppressWarnings("WeakerAccess")
	public HashMapResourceProvider(FhirContext theFhirContext, Class<T> theResourceType) {
		myFhirContext = theFhirContext;
		myResourceType = theResourceType;
		myResourceName = myFhirContext.getResourceDefinition(theResourceType).getName();
		clear();
	}

	/**
	 * Clear all data held in this resource provider
	 */
	public void clear() {
		myNextId = 1;
		myIdToVersionToResourceMap.clear();
	}

	/**
	 * Clear the counts used by {@link #getCountRead()} and other count methods
	 */
	public void clearCounts() {
		myReadCount.set(0L);
		myUpdateCount.set(0L);
		myCreateCount.set(0L);
		myDeleteCount.set(0L);
		mySearchCount.set(0L);
	}

	@Create
	public MethodOutcome create(@ResourceParam T theResource) {
		long idPart = myNextId++;
		String idPartAsString = Long.toString(idPart);
		Long versionIdPart = 1L;

		IIdType id = store(theResource, idPartAsString, versionIdPart);

		myCreateCount.incrementAndGet();

		return new MethodOutcome()
			.setCreated(true)
			.setId(id);
	}

	@Delete
	public MethodOutcome delete(@IdParam IIdType theId) {
		TreeMap<Long, T> versions = myIdToVersionToResourceMap.get(theId.getIdPart());
		if (versions == null || versions.isEmpty()) {
			throw new ResourceNotFoundException(theId);
		}


		long nextVersion = versions.lastEntry().getKey() + 1L;
		IIdType id = store(null, theId.getIdPart(), nextVersion);

		myDeleteCount.incrementAndGet();

		return new MethodOutcome()
			.setId(id);
	}

	/**
	 * This method returns a simple operation count. This is mostly
	 * useful for testing purposes.
	 */
	public long getCountCreate() {
		return myCreateCount.get();
	}

	/**
	 * This method returns a simple operation count. This is mostly
	 * useful for testing purposes.
	 */
	public long getCountDelete() {
		return myDeleteCount.get();
	}

	/**
	 * This method returns a simple operation count. This is mostly
	 * useful for testing purposes.
	 */
	public long getCountRead() {
		return myReadCount.get();
	}

	/**
	 * This method returns a simple operation count. This is mostly
	 * useful for testing purposes.
	 */
	public long getCountSearch() {
		return mySearchCount.get();
	}

	/**
	 * This method returns a simple operation count. This is mostly
	 * useful for testing purposes.
	 */
	public long getCountUpdate() {
		return myUpdateCount.get();
	}

	@Override
	public Class<? extends IBaseResource> getResourceType() {
		return myResourceType;
	}

	private synchronized TreeMap<Long, T> getVersionToResource(String theIdPart) {
		if (!myIdToVersionToResourceMap.containsKey(theIdPart)) {
			myIdToVersionToResourceMap.put(theIdPart, new TreeMap<>());
		}
		return myIdToVersionToResourceMap.get(theIdPart);
	}

	@Read(version = true)
	public IBaseResource read(@IdParam IIdType theId) {
		TreeMap<Long, T> versions = myIdToVersionToResourceMap.get(theId.getIdPart());
		if (versions == null || versions.isEmpty()) {
			throw new ResourceNotFoundException(theId);
		}

		T retVal;
		if (theId.hasVersionIdPart()) {
			Long versionId = theId.getVersionIdPartAsLong();
			if (!versions.containsKey(versionId)) {
				throw new ResourceNotFoundException(theId);
			} else {
				T resource = versions.get(versionId);
				if (resource == null) {
					throw new ResourceGoneException(theId);
				}
				retVal = resource;
			}

		} else {
			retVal = versions.lastEntry().getValue();
		}

		myReadCount.incrementAndGet();

		return retVal;
	}

	@Search
	public List<IBaseResource> search(
		@OptionalParam(name = "_id") TokenAndListParam theIds) {

		List<IBaseResource> retVal = new ArrayList<>();

		for (TreeMap<Long, T> next : myIdToVersionToResourceMap.values()) {
			if (next.isEmpty() == false) {
				T nextResource = next.lastEntry().getValue();

				boolean matches = true;
				if (theIds != null && theIds.getValuesAsQueryTokens().size() > 0) {
					for (TokenOrListParam nextIdAnd : theIds.getValuesAsQueryTokens()) {
						matches = false;
						for (TokenParam nextOr : nextIdAnd.getValuesAsQueryTokens()) {
							if (nextOr.getValue().equals(nextResource.getIdElement().getIdPart())) {
								matches = true;
							}
						}
						if (!matches) {
							break;
						}
					}
				}

				if (!matches) {
					continue;
				}

				retVal.add(nextResource);
			}
		}

		mySearchCount.incrementAndGet();

		return retVal;
	}

	private IIdType store(@ResourceParam T theResource, String theIdPart, Long theVersionIdPart) {
		IIdType id = myFhirContext.getVersion().newIdType();
		id.setParts(null, myResourceName, theIdPart, Long.toString(theVersionIdPart));
		if (theResource != null) {
			theResource.setId(id);
		}

		TreeMap<Long, T> versionToResource = getVersionToResource(theIdPart);
		versionToResource.put(theVersionIdPart, theResource);

		ourLog.info("Storing resource with ID: {}", id.getValue());

		return id;
	}

	@Update
	public MethodOutcome update(@ResourceParam T theResource) {

		String idPartAsString = theResource.getIdElement().getIdPart();
		TreeMap<Long, T> versionToResource = getVersionToResource(idPartAsString);

		Long versionIdPart;
		boolean created;
		if (versionToResource.isEmpty()) {
			versionIdPart = 1L;
			created = true;
		} else {
			versionIdPart = versionToResource.lastKey() + 1L;
			created = false;
		}

		IIdType id = store(theResource, idPartAsString, versionIdPart);

		myUpdateCount.incrementAndGet();

		return new MethodOutcome()
			.setCreated(created)
			.setId(id);
	}

}
