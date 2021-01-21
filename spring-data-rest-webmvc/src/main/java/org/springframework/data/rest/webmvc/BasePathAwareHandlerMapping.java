/*
 * Copyright 2014-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.rest.webmvc;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;

import org.springframework.data.rest.core.config.RepositoryRestConfiguration;
import org.springframework.data.util.ProxyUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.condition.ProducesRequestCondition;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo.BuilderConfiguration;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * A {@link RequestMappingHandlerMapping} that augments the request mappings
 *
 * @author Oliver Gierke
 */
public class BasePathAwareHandlerMapping extends RequestMappingHandlerMapping {

	private final RepositoryRestConfiguration configuration;

	private RequestMappingInfo emptyMapping;

	/**
	 * Creates a new {@link BasePathAwareHandlerMapping} using the given {@link RepositoryRestConfiguration}.
	 *
	 * @param configuration must not be {@literal null}.
	 */
	public BasePathAwareHandlerMapping(RepositoryRestConfiguration configuration) {

		Assert.notNull(configuration, "RepositoryRestConfiguration must not be null!");

		this.configuration = configuration;

		String baseUri = configuration.getBasePath().toString();

		if (StringUtils.hasText(baseUri)) {

			Map<String, Predicate<Class<?>>> prefixes = new HashMap<>();
			prefixes.put(baseUri, it -> true);

			this.setPathPrefixes(prefixes);
		}
	}


	@Override
	public void afterPropertiesSet() {
		super.afterPropertiesSet();
	}

	@Override
	protected void initHandlerMethods() {
		this.emptyMapping = RequestMappingInfo.paths().options(getBuilderConfiguration()).build();
		super.initHandlerMethods();
	}

	private BuilderConfiguration getBuilderConfiguration() {
		BuilderConfiguration config = new RequestMappingInfo.BuilderConfiguration();
		config.setTrailingSlashMatch(useTrailingSlashMatch());
		config.setContentNegotiationManager(getContentNegotiationManager());
		if (getPatternParser() != null) {
			config.setPatternParser(getPatternParser());
		}
		else {
			config.setSuffixPatternMatch(useSuffixPatternMatch());
			config.setRegisteredSuffixPatternMatch(useRegisteredSuffixPatternMatch());
			config.setPathMatcher(getPathMatcher());
		}
		return config;
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.web.servlet.handler.AbstractHandlerMethodMapping#lookupHandlerMethod(java.lang.String, javax.servlet.http.HttpServletRequest)
	 */
	@Override
	protected HandlerMethod lookupHandlerMethod(String lookupPath, HttpServletRequest request) throws Exception {

		List<MediaType> mediaTypes = new ArrayList<MediaType>();
		boolean defaultFound = false;

		for (MediaType mediaType : MediaType.parseMediaTypes(request.getHeader(HttpHeaders.ACCEPT))) {

			MediaType rawtype = mediaType.removeQualityValue();

			if (rawtype.equals(configuration.getDefaultMediaType())) {
				defaultFound = true;
			}

			if (!rawtype.equals(MediaType.ALL)) {
				mediaTypes.add(mediaType);
			}
		}

		if (!defaultFound) {
			mediaTypes.add(configuration.getDefaultMediaType());
		}

		return super.lookupHandlerMethod(lookupPath, new CustomAcceptHeaderHttpServletRequest(request, mediaTypes));
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.web.servlet.handler.AbstractHandlerMapping#hasCorsConfigurationSource(java.lang.Object)
	 */
	@Override
	protected boolean hasCorsConfigurationSource(Object handler) {
		return true;
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping#getMappingForMethod(java.lang.reflect.Method, java.lang.Class)
	 */
	@Override
	protected RequestMappingInfo getMappingForMethod(Method method, Class<?> handlerType) {

		RequestMappingInfo info = super.getMappingForMethod(method, handlerType);

		if (info == null) {
			return null;
		}

		ProducesRequestCondition producesCondition = customize(info.getProducesCondition());
		try {
			Field field = RequestMappingInfo.class.getDeclaredField("producesCondition");
			field.setAccessible(true);
			field.set(info, producesCondition);
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
		}

		return info;
	}

	/**
	 * Customize the given {@link ProducesRequestCondition}. Default implementation returns the condition as is.
	 *
	 * @param condition will never be {@literal null}.
	 * @return
	 */
	protected ProducesRequestCondition customize(ProducesRequestCondition condition) {
		return condition;
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping#isHandler(java.lang.Class)
	 */
	@Override
	protected boolean isHandler(Class<?> beanType) {

		Class<?> type = ProxyUtils.getUserClass(beanType);

		return type.isAnnotationPresent(BasePathAwareController.class);
	}

	/**
	 * {@link HttpServletRequest} that exposes the given media types for the {@code Accept} header.
	 *
	 * @author Oliver Gierke
	 */
	static class CustomAcceptHeaderHttpServletRequest extends HttpServletRequestWrapper {

		private final List<MediaType> acceptMediaTypes;
		private final List<String> acceptMediaTypeStrings;

		/**
		 * Creates a new {@link CustomAcceptHeaderHttpServletRequest} for the given delegate {@link HttpServletRequest} and
		 * the list of {@link MediaType}.
		 *
		 * @param request must not be {@literal null}.
		 * @param acceptMediaTypes must not be {@literal null} or empty.
		 */
		public CustomAcceptHeaderHttpServletRequest(HttpServletRequest request, List<MediaType> acceptMediaTypes) {

			super(request);

			Assert.notEmpty(acceptMediaTypes, "MediaTypes must not be empty!");

			this.acceptMediaTypes = acceptMediaTypes;

			List<String> acceptMediaTypeStrings = new ArrayList<String>(acceptMediaTypes.size());

			for (MediaType mediaType : acceptMediaTypes) {
				acceptMediaTypeStrings.add(mediaType.toString());
			}

			this.acceptMediaTypeStrings = acceptMediaTypeStrings;
		}

		/*
		 * (non-Javadoc)
		 * @see javax.servlet.http.HttpServletRequestWrapper#getHeader(java.lang.String)
		 */
		@Override
		public String getHeader(String name) {

			return HttpHeaders.ACCEPT.equalsIgnoreCase(name) && acceptMediaTypes != null //
					? StringUtils.collectionToCommaDelimitedString(acceptMediaTypes) //
					: super.getHeader(name);
		}

		/*
		 * (non-Javadoc)
		 * @see javax.servlet.http.HttpServletRequestWrapper#getHeaders(java.lang.String)
		 */
		@Override
		public Enumeration<String> getHeaders(String name) {

			return HttpHeaders.ACCEPT.equalsIgnoreCase(name) && acceptMediaTypes != null //
					? Collections.enumeration(acceptMediaTypeStrings) //
					: super.getHeaders(name);
		}
	}
}
