/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.wicket.ng;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.wicket.IPageFactory;
import org.apache.wicket.Page;
import org.apache.wicket.Request;
import org.apache.wicket.Response;
import org.apache.wicket.application.DefaultClassResolver;
import org.apache.wicket.application.IClassResolver;
import org.apache.wicket.ng.request.IRequestMapper;
import org.apache.wicket.ng.request.component.IRequestablePage;
import org.apache.wicket.ng.request.component.PageParametersNg;
import org.apache.wicket.ng.request.cycle.RequestCycle;
import org.apache.wicket.ng.request.cycle.RequestCycleContext;
import org.apache.wicket.ng.request.handler.impl.RenderPageRequestHandler;
import org.apache.wicket.ng.request.handler.impl.render.RenderPageRequestHandlerDelegate;
import org.apache.wicket.ng.request.listener.RequestListenerInterface;
import org.apache.wicket.ng.request.mapper.IMapperContext;
import org.apache.wicket.ng.request.mapper.ThreadsafeCompoundRequestMapper;
import org.apache.wicket.ng.resource.ResourceReferenceRegistry;
import org.apache.wicket.ng.session.ISessionStore;
import org.apache.wicket.ng.session.ISessionStore.UnboundListener;
import org.apache.wicket.ng.settings.IApplicationSettings;
import org.apache.wicket.ng.settings.IRequestCycleSettings;
import org.apache.wicket.pageStore.DefaultPageManagerContext;
import org.apache.wicket.pageStore.DefaultPageStore;
import org.apache.wicket.pageStore.DiskDataStore;
import org.apache.wicket.pageStore.IDataStore;
import org.apache.wicket.pageStore.IPageManager;
import org.apache.wicket.pageStore.IPageManagerContext;
import org.apache.wicket.pageStore.IPageStore;
import org.apache.wicket.pageStore.PersistentPageManager;
import org.apache.wicket.session.DefaultPageFactory;
import org.apache.wicket.util.lang.Checks;

/**
 * Generic application that is environment (servlet, portlet, test) agnostic.
 * 
 * @author Matej Knopp
 */
public abstract class Application implements UnboundListener
{
	private String name;

	public Application()
	{
	}

	protected void internalInit()
	{
		sessionStore = newSessionStore();
		sessionStore.registerUnboundListener(this);
		pageManager = newPageManager(getPageManagerContext());
		rootRequestMapper = newRequestHandlerEncoderRegistry();
		resourceReferenceRegistry = newResourceReferenceRegistry();
		pageFactory = newPageFactory();
		registerDefaultEncoders();
	}

	protected void init()
	{

	}

	/**
	 * Initialize the application
	 */
	public final void initApplication()
	{
		if (name == null)
		{
			throw new IllegalStateException("setName must be called before initApplication");
		}
		internalInit();
		init();
	}

	/**
	 * Sets application name. This method must be called before any other methods are invoked and
	 * can only be called once per application instance.
	 * 
	 * @param name
	 *            unique application name
	 */
	public final void setName(String name)
	{
		Checks.argumentNotEmpty(name, "name");

		if (this.name != null)
		{
			throw new IllegalStateException("Application name can only be set once.");
		}

		if (applications.get(name) != null)
		{
			throw new IllegalStateException("Application with name '" + name + "' already exists.'");
		}

		this.name = name;
		applications.put(name, this);
	}

	/**
	 * Returns the name unique for this application.
	 * 
	 * @return
	 */
	public String getName()
	{
		return name;
	}

	/**
	 * Destroys the application.
	 */
	public void destroy()
	{
		try
		{
			pageManager.destroy();
			sessionStore.destroy();
		}
		finally
		{
			applications.remove(name);
		}
	}

	/**
	 * Returns application with given name or <code>null</code> if such application is not
	 * registered.
	 * 
	 * @param name
	 * @return
	 */
	public static Application get(String name)
	{
		return applications.get(name);
	}

	/**
	 * Returns application attached to current thread.
	 * 
	 * @return
	 * @throws WicketRuntimeException
	 *             no application is attached to current thread
	 */
	public static Application get()
	{
		Application application = ThreadContext.getApplication();
		if (application == null)
		{
			throw new WicketRuntimeException("There is no application attached to current thread " +
				Thread.currentThread().getName());
		}
		return application;
	}

	/**
	 * Returns whether there is application attached to current thread.
	 * 
	 * @return
	 */
	public static boolean exists()
	{
		return ThreadContext.getApplication() != null;
	}

	/**
	 * Assign this application to current thread. This method should never be called by framework
	 * clients.
	 */
	public void set()
	{
		ThreadContext.setApplication(this);
	}

	private static Map<String, Application> applications = new ConcurrentHashMap<String, Application>();

	// /////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
	// /////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
	//
	// Settings
	//
	// /////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
	// /////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

	// TODO - Do properly
	private final IRequestCycleSettings settings = new IRequestCycleSettings()
	{
		private RenderStrategy strategy = RenderStrategy.REDIRECT_TO_BUFFER;
		private String responseEncoding = "UTF-8";

		public RenderStrategy getRenderStrategy()
		{
			return strategy;
		}

		public String getResponseRequestEncoding()
		{
			return responseEncoding;
		}

		public void setRenderStrategy(RenderStrategy renderStrategy)
		{
			strategy = renderStrategy;
		}

		public void setResponseRequestEncoding(String responseRequestEncoding)
		{
			responseEncoding = responseRequestEncoding;
		}
	};

	// TODO: - Do properly
	private final IApplicationSettings applicationSettings = new IApplicationSettings()
	{
		private IClassResolver resolver = new DefaultClassResolver();

		public IClassResolver getClassResolver()
		{
			return resolver;
		}

		public void setClassResolver(IClassResolver defaultClassResolver)
		{
			resolver = defaultClassResolver;
		}
	};

	public IRequestCycleSettings getRequestCycleSettings()
	{
		return settings;
	}

	public IApplicationSettings getApplicationSettings()
	{
		return applicationSettings;
	}

	// /////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
	// /////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
	//
	// Request Cycle
	//
	// /////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
	// /////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////


	/**
	 * Override this method to create custom Request Cycle instance.
	 * 
	 * @param context
	 *            holds context necessary to instantiate a request cycle, such as the current
	 *            request, response, and request mappers
	 * @return
	 */
	protected RequestCycle newRequestCycle(RequestCycleContext context)
	{

		return new RequestCycle(context);
	}

	public final RequestCycle createRequestCycle(Request request, Response response)
	{
		// FIXME exception mapper should come from elsewhere
		RequestCycleContext context = new RequestCycleContext(request, response,
			getRootRequestMapper(), new DefaultExceptionMapper());

		RequestCycle requestCycle = newRequestCycle(context);
		requestCycle.register(new RequestCycle.DetachCallback()
		{
			public void onDetach(RequestCycle requestCycle)
			{
				getPageManager().commitRequest();
			}
		});
		return requestCycle;
	}

	// /////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
	// /////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
	//
	// Session Store
	//
	// /////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
	// /////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

	protected abstract ISessionStore newSessionStore();

	private ISessionStore sessionStore;

	public ISessionStore getSessionStore()
	{
		return sessionStore;
	}

	public void sessionUnbound(String sessionId)
	{
		getPageManager().sessionExpired(sessionId);
	}

	// /////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
	// /////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
	//
	// Session
	//
	// /////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
	// /////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

	protected Session newSession(RequestCycle requestCycle)
	{
		return new Session(requestCycle);
	}

	Session fetchCreateAndSetSession(RequestCycle requestCycle)
	{
		Checks.argumentNotNull(requestCycle, "requestCycle");

		Session session = getSessionStore().lookup(requestCycle.getRequest());
		if (session == null)
		{
			session = newSession(requestCycle);
			ThreadContext.setSession(session);
			getPageManager().newSessionCreated();
		}
		else
		{
			ThreadContext.setSession(session);
		}
		return session;
	}

	// /////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
	// /////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
	//
	// Page Manager
	//
	// /////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
	// /////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

	protected IPageManager newPageManager(IPageManagerContext context)
	{
		int cacheSize = 40;
		int fileChannelPoolCapacity = 50;
		IDataStore dataStore = new DiskDataStore(getName(), 1000000, fileChannelPoolCapacity);
		IPageStore pageStore = new DefaultPageStore(getName(), dataStore, cacheSize);
		return new PersistentPageManager(getName(), pageStore, context);
	}

	private IPageManager pageManager;

	/**
	 * Context for PageManager to interact with rest of Wicket
	 */
	private final IPageManagerContext pageManagerContext = new DefaultPageManagerContext();

	/**
	 * 
	 * @return the page manager
	 */
	public IPageManager getPageManager()
	{
		return pageManager;
	}

	/**
	 * 
	 * @return the page manager context
	 */
	protected IPageManagerContext getPageManagerContext()
	{
		return pageManagerContext;
	}

	// /////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
	// /////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
	//
	// Page Rendering
	//
	// /////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
	// /////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

	/**
	 * Returns the {@link RenderPageRequestHandlerDelegate} responsible for rendering the page.
	 */
	public abstract RenderPageRequestHandlerDelegate getRenderPageRequestHandlerDelegate(
		RenderPageRequestHandler renderPageRequestHandler);

	// /////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
	// /////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
	//
	// Request Handler encoding
	//
	// /////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
	// /////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

	/**
	 * Application subclasses must specify a home page class by implementing this abstract method.
	 * 
	 * @return Home page class for this application
	 */
	public abstract Class<? extends IRequestablePage> getHomePage();

	private ThreadsafeCompoundRequestMapper rootRequestMapper;

	/**
	 * Override to create custom {@link ThreadsafeCompoundRequestMapper}.
	 * 
	 * @return new {@link ThreadsafeCompoundRequestMapper} instance
	 */
	protected ThreadsafeCompoundRequestMapper newRequestHandlerEncoderRegistry()
	{
		return new ThreadsafeCompoundRequestMapper();
	};

	/**
	 * returns the {@link ThreadsafeCompoundRequestMapper} for this application.
	 * 
	 * @return
	 */
	public final ThreadsafeCompoundRequestMapper getRootRequestMapper()
	{
		return rootRequestMapper;
	}

	private ResourceReferenceRegistry resourceReferenceRegistry;

	/**
	 * Override to create custom {@link ResourceReferenceRegistry}.
	 * 
	 * @return new {@link ResourceReferenceRegistry} instance.
	 */
	protected ResourceReferenceRegistry newResourceReferenceRegistry()
	{
		return new ResourceReferenceRegistry();
	}

	/**
	 * Returns {@link ResourceReferenceRegistry} for this application.
	 * 
	 * @return
	 */
	public final ResourceReferenceRegistry getResourceReferenceRegistry()
	{
		return resourceReferenceRegistry;
	}

	private IPageFactory pageFactory;

	/**
	 * Override to create custom {@link PageFactory}
	 * 
	 * @return new {@link PageFactory} instance.
	 */
	protected IPageFactory newPageFactory()
	{
		return new DefaultPageFactory();
	}

	/**
	 * Returns {@link PageFactory} for this application.
	 * 
	 * @return
	 */
	public final IPageFactory getPageFactory()
	{
		return pageFactory;
	}

	private final IMapperContext encoderContext = new IMapperContext()
	{
		public String getBookmarkableIdentifier()
		{
			return "bookmarkable";
		}

		public String getNamespace()
		{
			return "wicket";
		}

		public String getPageIdentifier()
		{
			return "page";
		}

		public String getResourceIdentifier()
		{
			return "resource";
		}

		public ResourceReferenceRegistry getResourceReferenceRegistry()
		{
			return Application.this.getResourceReferenceRegistry();
		}

		public RequestListenerInterface requestListenerInterfaceFromString(String interfaceName)
		{
			return RequestListenerInterface.forName(interfaceName);
		}

		public String requestListenerInterfaceToString(RequestListenerInterface listenerInterface)
		{
			return listenerInterface.getName();
		}

		public IRequestablePage newPageInstance(Class<? extends IRequestablePage> pageClass,
			PageParametersNg pageParameters)
		{
			if (pageParameters == null)
			{
				return getPageFactory().newPage((Class<? extends Page>)pageClass);
			}
			else
			{
				return getPageFactory().newPage((Class<? extends Page>)pageClass, pageParameters);
			}
		}

		public IRequestablePage getPageInstance(int pageId)
		{
			return Page.getPage(pageId);
		}

		public Class<? extends IRequestablePage> getHomePageClass()
		{
			return Application.this.getHomePage();
		}
	};

	public final IMapperContext getEncoderContext()
	{
		return encoderContext;
	}

	public void registerEncoder(IRequestMapper encoder)
	{
		getRootRequestMapper().register(encoder);
	}

	/**
	 * Register the default encoders - necessary for the application to work.
	 */
	protected void registerDefaultEncoders()
	{
	}
}
