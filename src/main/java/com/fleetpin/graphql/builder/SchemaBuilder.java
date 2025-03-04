/*
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package com.fleetpin.graphql.builder;

import com.fleetpin.graphql.builder.annotations.Directive;
import com.fleetpin.graphql.builder.annotations.Entity;
import com.fleetpin.graphql.builder.annotations.Mutation;
import com.fleetpin.graphql.builder.annotations.Query;
import com.fleetpin.graphql.builder.annotations.Restrict;
import com.fleetpin.graphql.builder.annotations.Restricts;
import com.fleetpin.graphql.builder.annotations.SchemaOption;
import com.fleetpin.graphql.builder.annotations.Subscription;
import graphql.schema.GraphQLScalarType;
import graphql.schema.GraphQLSchema;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.reflections.Reflections;
import org.reflections.scanners.Scanners;

public class SchemaBuilder {

	private final DirectivesSchema diretives;
	private final AuthorizerSchema authorizer;

	private final EntityProcessor entityProcessor;

	private SchemaBuilder(List<GraphQLScalarType> scalars, DirectivesSchema diretives, AuthorizerSchema authorizer) {
		this.diretives = diretives;
		this.authorizer = authorizer;

		this.entityProcessor = new EntityProcessor(scalars, diretives);

		diretives.processSDL(entityProcessor);
	}

	private SchemaBuilder processTypes(Set<Class<?>> types) {
		for (var type : types) {
			TypeMeta meta = new TypeMeta(null, type, type);

			var annotation = type.getAnnotation(Entity.class);
			if (annotation.value() != SchemaOption.INPUT) {
				this.entityProcessor.getEntity(meta).getInnerType(meta);
			}
		}
		return this;
	}

	private SchemaBuilder process(HashSet<Method> endPoints) throws ReflectiveOperationException {
		var methodProcessor = this.entityProcessor.getMethodProcessor();
		for (var method : endPoints) {
			methodProcessor.process(authorizer, method);
		}

		return this;
	}

	private graphql.schema.GraphQLSchema.Builder build(Set<Class<? extends SchemaConfiguration>> schemaConfiguration) {
		var methods = entityProcessor.getMethodProcessor();

		var builder = GraphQLSchema.newSchema().codeRegistry(methods.getCodeRegistry().build()).additionalTypes(entityProcessor.getAdditionalTypes());

		var query = methods.getGraphQuery().build();
		builder.query(query);

		var mutations = methods.getGraphMutations().build();
		if (!mutations.getFields().isEmpty()) {
			builder.mutation(mutations);
		}
		var subscriptions = methods.getGraphSubscriptions().build();
		if (!subscriptions.getFields().isEmpty()) {
			builder.subscription(subscriptions);
		}

		diretives.getSchemaDirective().forEach(directive -> builder.additionalDirective(directive));

		for (var schema : schemaConfiguration) {
			this.diretives.addSchemaDirective(schema, schema, builder::withSchemaAppliedDirective);
		}
		return builder;
	}

	public static GraphQLSchema build(String... classPath) {
		return builder(classPath).build();
	}

	public static GraphQLSchema.Builder builder(String... classpath) {
		var builder = builder();
		for (var path : classpath) {
			builder.classpath(path);
		}
		return builder.build();
	}

	public static Builder builder() {
		return new Builder();
	}

	public static class Builder {

		private List<String> classpaths = new ArrayList<>();
		private List<GraphQLScalarType> scalars = new ArrayList<>();

		private Builder() {}

		public Builder classpath(String classpath) {
			this.classpaths.add(classpath);
			return this;
		}

		public Builder scalar(GraphQLScalarType scalar) {
			this.scalars.add(scalar);
			return this;
		}

		public GraphQLSchema.Builder build() {
			try {
				Reflections reflections = new Reflections(classpaths, Scanners.SubTypes, Scanners.MethodsAnnotated, Scanners.TypesAnnotated);
				Set<Class<? extends Authorizer>> authorizers = reflections.getSubTypesOf(Authorizer.class);
				//want to make everything split by package
				AuthorizerSchema authorizer = AuthorizerSchema.build(new HashSet<>(classpaths), authorizers);

				Set<Class<? extends SchemaConfiguration>> schemaConfiguration = reflections.getSubTypesOf(SchemaConfiguration.class);

				Set<Class<?>> dierctivesTypes = reflections.getTypesAnnotatedWith(Directive.class);

				Set<Class<?>> restrict = reflections.getTypesAnnotatedWith(Restrict.class);
				Set<Class<?>> restricts = reflections.getTypesAnnotatedWith(Restricts.class);
				List<RestrictTypeFactory<?>> globalRestricts = new ArrayList<>();

				for (var r : restrict) {
					Restrict annotation = r.getAnnotation(Restrict.class);
					var factoryClass = annotation.value();
					var factory = factoryClass.getConstructor().newInstance();
					if (!factory.extractType().isAssignableFrom(r)) {
						throw new RuntimeException(
							"Restrict annotation does match class applied to targets" + factory.extractType() + " but was on class " + r
						);
					}
					globalRestricts.add(factory);
				}

				for (var r : restricts) {
					Restricts annotations = r.getAnnotation(Restricts.class);
					for (Restrict annotation : annotations.value()) {
						var factoryClass = annotation.value();
						var factory = factoryClass.getConstructor().newInstance();

						if (!factory.extractType().isAssignableFrom(r)) {
							throw new RuntimeException(
								"Restrict annotation does match class applied to targets" + factory.extractType() + " but was on class " + r
							);
						}
						globalRestricts.add(factory);
					}
				}

				DirectivesSchema diretivesSchema = DirectivesSchema.build(globalRestricts, dierctivesTypes);

				Set<Class<?>> types = reflections.getTypesAnnotatedWith(Entity.class);

				var mutations = reflections.getMethodsAnnotatedWith(Mutation.class);
				var subscriptions = reflections.getMethodsAnnotatedWith(Subscription.class);
				var queries = reflections.getMethodsAnnotatedWith(Query.class);

				var endPoints = new HashSet<>(mutations);
				endPoints.addAll(subscriptions);
				endPoints.addAll(queries);

				types.removeIf(t -> t.getDeclaredAnnotation(Entity.class) == null);
				types.removeIf(t -> t.isAnonymousClass());

				return new SchemaBuilder(scalars, diretivesSchema, authorizer).processTypes(types).process(endPoints).build(schemaConfiguration);
			} catch (ReflectiveOperationException e) {
				throw new RuntimeException(e);
			}
		}
	}
}
