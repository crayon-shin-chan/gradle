/*
 * Copyright 2013 the original author or authors.
 *
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
 */

package org.gradle.model.internal.registry;

import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.collect.*;
import net.jcip.annotations.NotThreadSafe;
import org.gradle.api.Action;
import org.gradle.api.Nullable;
import org.gradle.api.Transformer;
import org.gradle.internal.Actions;
import org.gradle.internal.Transformers;
import org.gradle.model.InvalidModelRuleException;
import org.gradle.model.ModelRuleBindingException;
import org.gradle.model.internal.core.*;
import org.gradle.model.internal.core.rule.describe.ModelRuleDescriptor;
import org.gradle.model.internal.report.AmbiguousBindingReporter;
import org.gradle.model.internal.report.IncompatibleTypeReferenceReporter;
import org.gradle.model.internal.report.unbound.UnboundRule;
import org.gradle.model.internal.type.ModelType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

@NotThreadSafe
public class DefaultModelRegistry implements ModelRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultModelRegistry.class);

    /*
                Things we aren't doing and should:

                - Detecting cycles between config rules
                - Detecting dangling, unbound, rules
                - Detecting model elements with no object at their parent path
                - Detecting mutation rules registering parent model
                - Detecting a rule binding the same input twice (maybe that's ok)
                - Detecting a rule trying to bind the same element to mutate and to read
                - Detecting adding mutation rule during finalization
             */
    private final ModelGraph modelGraph = new ModelGraph();

    private final Map<ModelPath, BoundModelCreator> creators = Maps.newHashMap();
    private final Multimap<MutationKey, BoundModelMutator<?>> mutators = ArrayListMultimap.create();
    private final Multimap<ModelPath, List<ModelPath>> usedMutators = ArrayListMultimap.create();

    // TODO rework this listener mechanism to be more targeted, in that interested parties nominate what they are interested in (e.g. path or type) and use this to only notify relevant listeners
    private final List<ModelCreationListener> modelCreationListeners = new LinkedList<ModelCreationListener>();

    private final List<RuleBinder<?>> binders = Lists.newLinkedList();

    private static String toString(ModelRuleDescriptor descriptor) {
        StringBuilder stringBuilder = new StringBuilder();
        descriptor.describeTo(stringBuilder);
        return stringBuilder.toString();
    }

    public void create(ModelCreator creator) {
        ModelPath path = creator.getPath();
        if (path.getDepth() > 1) {
            throw new IllegalStateException("Creator at path " + path + " not supported, must be top level");
        }

        // Disabled before 2.3 release due to not wanting to validate task names (which may contain invalid chars), at least not yet
        // ModelPath.validateName(name);

        ModelNode node = modelGraph.find(path);
        if (node != null) {
            if (node.getState() == ModelNode.State.Known) {
                throw new DuplicateModelException(
                        String.format(
                                "Cannot register model creation rule '%s' for path '%s' as the rule '%s' is already registered to create a model element at this path",
                                toString(creator.getDescriptor()),
                                path,
                                toString(node.getDescriptor())
                        )
                );
            }
            throw new DuplicateModelException(
                    String.format(
                            "Cannot register model creation rule '%s' for path '%s' as the rule '%s' is already registered (and the model element has been created)",
                            toString(creator.getDescriptor()),
                            path,
                            toString(node.getDescriptor())
                    )
            );
        }

        node = modelGraph.addEntryPoint(path, creator.getDescriptor(), creator.getPromise(), creator.getAdapter());
        notifyCreationListeners(node);
        bind(creator);
    }

    @Override
    public <T> void mutate(MutationType type, ModelMutator<T> mutator) {
        bind(mutator.getSubject(), type, mutator);
    }

    private void bind(final ModelCreator creator) {
        final RuleBinder<Void> binder = bind(null, creator.getInputs(), creator.getDescriptor(), new Action<RuleBinder<Void>>() {
            public void execute(RuleBinder<Void> ruleBinding) {
                BoundModelCreator boundCreator = new BoundModelCreator(creator, ruleBinding.getInputBindings());
                creators.put(creator.getPath(), boundCreator);
            }
        });

        bindInputs(binder);
    }

    @SuppressWarnings("unchecked")
    private <T> RuleBinder<T> bind(ModelReference<T> subject, List<? extends ModelReference<?>> inputs, ModelRuleDescriptor descriptor, Action<? super RuleBinder<T>> onBind) {
        RuleBinder<T> binder = new RuleBinder<T>(subject, inputs, descriptor, Actions.composite(new Action<RuleBinder<T>>() {
            public void execute(RuleBinder<T> binder) {
                // TODO this is going to run even if we never added the binder to the bindings (inefficient)
                binders.remove(binder);
            }
        }, onBind));

        if (!binder.isBound()) {
            binders.add(binder);
        }

        return binder;
    }

    private <T> void bind(ModelReference<T> subject, final MutationType type, final ModelMutator<T> mutator) {
        final RuleBinder<T> binder = bind(subject, mutator.getInputs(), mutator.getDescriptor(), new Action<RuleBinder<T>>() {
            public void execute(RuleBinder<T> ruleBinder) {
                BoundModelMutator<T> boundMutator = new BoundModelMutator<T>(mutator, ruleBinder.getSubjectBinding(), ruleBinder.getInputBindings());
                ModelPath path = boundMutator.getSubject().getPath();
                mutators.put(new MutationKey(path, type), boundMutator);
            }
        });

        registerListener(new BinderCreationListener(binder.getDescriptor(), binder.getSubjectReference(), true, new Action<ModelPath>() {
            public void execute(ModelPath modelPath) {
                binder.bindSubject(modelPath);
            }
        }));

        bindInputs(binder);
    }

    private void bindInputs(final RuleBinder<?> binder) {
        List<? extends ModelReference<?>> inputReferences = binder.getInputReferences();
        for (int i = 0; i < inputReferences.size(); i++) {
            final int finalI = i;
            registerListener(new BinderCreationListener(binder.getDescriptor(), inputReferences.get(i), false, new Action<ModelPath>() {
                public void execute(ModelPath modelPath) {
                    binder.bindInput(finalI, modelPath);
                }
            }));
        }
    }

    public <T> T get(ModelPath path, ModelType<T> type) {
        return toType(type, require(path), "get(ModelPath, ModelType)");
    }

    public <T> T find(ModelPath path, ModelType<T> type) {
        return toType(type, get(path), "find(ModelPath, ModelType)");
    }

    private <T> T toType(ModelType<T> type, ModelNode node, String msg) {
        if (node == null) {
            return null;
        } else {
            return assertView(node, type, null, msg).getInstance();
        }
    }

    public MutableModelNode node(ModelPath path) {
        return new NodeWrapper(require(path));
    }

    public void registerListener(ModelCreationListener listener) {
        boolean remove;

        // Copy the creations we know about now because a listener may add creations, causing a CME.
        // This can happen when a listener is listening in order to bind a type-only reference, and the
        // reference binding causing the rule to fully bind and register a new creation.
        List<ModelNode> currentCreations = Lists.newArrayList(modelGraph.getFlattened().values());

        for (ModelCreation creation : currentCreations) {
            remove = listener.onCreate(creation);
            if (remove) {
                return;
            }
        }

        modelCreationListeners.add(listener);
    }

    public void remove(ModelPath path) {
        if (isDependedOn(path)) {
            throw new RuntimeException("Tried to remove model " + path + " but it is depended on by other model elements");
        }

        creators.remove(path);
        modelGraph.remove(path);
    }

    public void validate() throws UnboundModelRulesException {
        if (binders.isEmpty()) {
            return;
        }

        // Some rules may not have bound because their references are to nested properties
        // This can happen, for example, if the reference is to a property of a managed model element
        // Here, we look for such “non root” bindings where we know the parent exists.
        // If we find such unbound references, we force the creation of the parent to try and bind the nested reference.

        // TODO if we push more knowledge of nested properties up out of constructors, we can potentially bind such references without creating the parent chain.

        while (!binders.isEmpty()) {
            Iterable<ModelPath> unboundPaths = Iterables.concat(Iterables.transform(binders, new Function<RuleBinder<?>, Iterable<ModelPath>>() {
                public Iterable<ModelPath> apply(RuleBinder<?> input) {
                    return input.getUnboundPaths();
                }
            }));

            ModelPath unboundTopLevelModelPath = Iterables.find(unboundPaths, new Predicate<ModelPath>() {
                public boolean apply(ModelPath input) {
                    return input.getRootParent() != null;
                }
            }, null);

            if (unboundTopLevelModelPath != null) {
                ModelPath rootParent = unboundTopLevelModelPath.getRootParent();
                if (creators.containsKey(rootParent)) {
                    get(rootParent);
                } else {
                    break;
                }
            } else {
                break;
            }
        }

        if (!binders.isEmpty()) {
            ModelPathSuggestionProvider suggestionsProvider = new ModelPathSuggestionProvider(Iterables.concat(modelGraph.getFlattened().keySet(), creators.keySet()));
            List<? extends UnboundRule> unboundRules = new UnboundRulesProcessor(binders, suggestionsProvider).process();
            throw new UnboundModelRulesException(unboundRules);
        }
    }

    // TODO - this needs to consider partially bound rules
    private boolean isDependedOn(ModelPath candidate) {
        Transformer<Iterable<ModelPath>, BoundModelMutator<?>> extractInputPaths = new Transformer<Iterable<ModelPath>, BoundModelMutator<?>>() {
            public Iterable<ModelPath> transform(BoundModelMutator<?> original) {
                return Iterables.transform(original.getInputs(), new Function<ModelBinding<?>, ModelPath>() {
                    @Nullable
                    public ModelPath apply(ModelBinding<?> input) {
                        return input.getPath();
                    }
                });
            }
        };

        Transformer<List<ModelPath>, List<ModelPath>> passThrough = Transformers.noOpTransformer();

        return hasModelPath(candidate, mutators.values(), extractInputPaths)
                || hasModelPath(candidate, usedMutators.values(), passThrough);
    }

    private <T> boolean hasModelPath(ModelPath candidate, Iterable<T> things, Transformer<? extends Iterable<ModelPath>, T> transformer) {
        for (T thing : things) {
            for (ModelPath path : transformer.transform(thing)) {
                if (path.equals(candidate)) {
                    return true;
                }
            }
        }

        return false;
    }

    private ModelNode require(ModelPath path) {
        ModelNode node = get(path);
        if (node == null) {
            throw new IllegalStateException("No model node at '" + path + "'");
        }
        return node;
    }

    private ModelNode get(ModelPath path) {
        ModelNode node = modelGraph.find(path);
        if (node == null) {
            return null;
        }
        close(node);
        return node;
    }

    private void close(ModelNode node) {
        if (node.getState() == ModelNode.State.GraphClosed) {
            return;
        }

        ModelPath path = node.getPath();
        LOGGER.debug("Closing {}", path);

        maybeCreate(node);
        maybeMutate(node);
        maybeCloseLinked(node);

        LOGGER.debug("Finished closing {}", path);
    }

    private void maybeCloseLinked(ModelNode node) {
        ModelPath path = node.getPath();
        if (node.getState() == ModelNode.State.SelfClosed) {
            for (ModelNode child : node.getLinks().values()) {
                close(child);
            }
            fireMutations(node, path, mutators.removeAll(new MutationKey(path, MutationType.Validate)), usedMutators);
            node.setState(ModelNode.State.GraphClosed);
        }
    }

    private void maybeMutate(ModelNode node) {
        ModelPath path = node.getPath();
        if (node.getState() == ModelNode.State.Created) {
            fireMutations(node, path, mutators.removeAll(new MutationKey(path, MutationType.Defaults)), usedMutators);
            fireMutations(node, path, mutators.removeAll(new MutationKey(path, MutationType.Initialize)), usedMutators);
            fireMutations(node, path, mutators.removeAll(new MutationKey(path, MutationType.Mutate)), usedMutators);
            fireMutations(node, path, mutators.removeAll(new MutationKey(path, MutationType.Finalize)), usedMutators);
            node.setState(ModelNode.State.SelfClosed);
        }
    }

    private void maybeCreate(ModelNode node) {
        ModelPath path = node.getPath();
        if (node.getState() == ModelNode.State.Known) {
            BoundModelCreator creator = creators.remove(path);
            if (creator == null) {
                // Unbound creator - should give better error message here
                throw new IllegalStateException("Don't know how to create model element at '" + path + "'");
            }
            doCreate(node, creator);
            node.setState(ModelNode.State.Created);
        }
    }

    private void fireMutations(ModelNode node, ModelPath path, Iterable<BoundModelMutator<?>> mutators, Multimap<ModelPath, List<ModelPath>> used) {
        for (BoundModelMutator<?> mutator : mutators) {
            fireMutation(node, mutator);
            List<ModelPath> inputPaths = Lists.transform(mutator.getInputs(), new Function<ModelBinding<?>, ModelPath>() {
                @Nullable
                public ModelPath apply(ModelBinding<?> input) {
                    return input.getPath();
                }
            });
            used.put(path, inputPaths);
        }
    }

    private <T> ModelView<? extends T> assertView(ModelNode node, ModelType<T> targetType, @Nullable ModelRuleDescriptor descriptor, String msg, Object... msgArgs) {
        ModelAdapter adapter = node.getAdapter();
        ModelView<? extends T> view = adapter.asReadOnly(targetType, new NodeWrapper(node), descriptor);
        if (view == null) {
            // TODO better error reporting here
            throw new IllegalArgumentException("Model node is not compatible with requested " + targetType + " (operation: " + String.format(msg, msgArgs) + ")");
        } else {
            return view;
        }
    }

    private <T> ModelView<? extends T> assertView(ModelNode node, ModelBinding<T> binding, ModelRuleDescriptor sourceDescriptor, Inputs inputs) {
        ModelAdapter adapter = node.getAdapter();
        ModelView<? extends T> view = adapter.asWritable(binding.getReference().getType(), new NodeWrapper(node), sourceDescriptor, inputs);
        if (view == null) {
            // TODO better error reporting here
            throw new IllegalArgumentException("Cannot project model element " + binding.getPath() + " to writable type '" + binding.getReference().getType() + "' for rule " + sourceDescriptor);
        } else {
            return view;
        }
    }

    private ModelNode doCreate(ModelNode node, BoundModelCreator boundCreator) {
        ModelCreator creator = boundCreator.getCreator();
        Inputs inputs = toInputs(boundCreator.getInputs(), boundCreator.getCreator().getDescriptor());

        LOGGER.debug("Creating {} using {}", node.getPath(), creator.getDescriptor());

        try {
            creator.create(new NodeWrapper(node), inputs);
        } catch (Exception e) {
            // TODO some representation of state of the inputs
            throw new ModelRuleExecutionException(creator.getDescriptor(), e);
        }

        return node;
    }

    private <T> void fireMutation(ModelNode node, BoundModelMutator<T> boundMutator) {
        Inputs inputs = toInputs(boundMutator.getInputs(), boundMutator.getMutator().getDescriptor());
        ModelMutator<T> mutator = boundMutator.getMutator();
        ModelRuleDescriptor descriptor = mutator.getDescriptor();

        LOGGER.debug("Mutating {} using {}", node.getPath(), mutator.getDescriptor());

        ModelView<? extends T> view = assertView(node, boundMutator.getSubject(), descriptor, inputs);
        try {
            mutator.mutate(new NodeWrapper(node), view.getInstance(), inputs);
        } catch (Exception e) {
            // TODO some representation of state of the inputs
            throw new ModelRuleExecutionException(descriptor, e);
        } finally {
            view.close();
        }
    }

    private Inputs toInputs(Iterable<? extends ModelBinding<?>> bindings, ModelRuleDescriptor ruleDescriptor) {
        ImmutableList.Builder<ModelRuleInput<?>> builder = ImmutableList.builder();
        for (ModelBinding<?> binding : bindings) {
            ModelRuleInput<?> input = toInput(binding, ruleDescriptor);
            builder.add(input);
        }
        return new DefaultInputs(builder.build());
    }

    private <T> ModelRuleInput<T> toInput(ModelBinding<T> binding, ModelRuleDescriptor ruleDescriptor) {
        ModelPath path = binding.getPath();
        ModelNode element = require(path);
        ModelView<? extends T> view = assertView(element, binding.getReference().getType(), ruleDescriptor, "toInputs");
        return ModelRuleInput.of(binding, view);
    }

    private void notifyCreationListeners(ModelCreation creation) {
        // Deal with listeners that add listeners. This could be more elegant
        List<ModelCreationListener> discard = new ArrayList<ModelCreationListener>();
        for (ModelCreationListener listener : new ArrayList<ModelCreationListener>(modelCreationListeners)) {
            boolean remove = listener.onCreate(creation);
            if (remove) {
                discard.add(listener);
            }
        }
        modelCreationListeners.removeAll(discard);
    }

    private class NodeWrapper implements MutableModelNode {
        private final ModelNode node;

        public NodeWrapper(ModelNode node) {
            this.node = node;
        }

        public ModelPromise getPromise() {
            return node.getPromise();
        }

        public ModelAdapter getAdapter() {
            return node.getAdapter();
        }

        @Override
        public ModelPath getPath() {
            return node.getPath();
        }

        @Nullable
        @Override
        public <T> ModelView<? extends T> asReadOnly(ModelType<T> type, @Nullable ModelRuleDescriptor ruleDescriptor) {
            return node.getAdapter().asReadOnly(type, this, ruleDescriptor);
        }

        @Nullable
        @Override
        public <T> ModelView<? extends T> asWritable(ModelType<T> type, ModelRuleDescriptor ruleDescriptor, @Nullable Inputs inputs) {
            return node.getAdapter().asWritable(type, this, ruleDescriptor, inputs);
        }

        @Nullable
        @Override
        public MutableModelNode getLink(String name) {
            ModelNode node = this.node.getLink(name);
            return node == null ? null : new NodeWrapper(node);
        }

        @Override
        public int getLinkCount(ModelType<?> type) {
            int count = 0;
            for (ModelNode linked : node.getLinks().values()) {
                if (linked.getPromise().canBeViewedAsWritable(type)) {
                    count++;
                }
            }
            return count;
        }

        @Override
        public boolean hasLink(String name) {
            return node.hasLink(name);
        }

        @Override
        public <T> void mutateLink(MutationType type, ModelMutator<T> mutator) {
            if (mutator.getSubject().getPath() == null || !mutator.getSubject().getPath().getParent().equals(getPath())) {
                throw new IllegalArgumentException("Mutator reference must have path to child of this node.");
            }
            bind(mutator.getSubject(), type, mutator);
        }

        @Override
        public <T> void mutateAllLinks(final MutationType type, final ModelMutator<T> mutator) {
            if (mutator.getSubject().getPath() != null) {
                throw new IllegalArgumentException("Mutator reference must have null path.");
            }

            registerListener(new ModelCreationListener() {
                @Override
                public boolean onCreate(ModelCreation registration) {
                    ModelType<T> subjectType = mutator.getSubject().getType();
                    if (node.getPath().equals(registration.getPath().getParent()) && registration.getPromise().canBeViewedAsWritable(subjectType)) {
                        bind(ModelReference.of(registration.getPath(), subjectType), type, mutator);
                    }
                    return false;
                }
            });
        }

        @Override
        public MutableModelNode addLink(ModelCreator creator) {
            // Disabled before 2.3 release due to not wanting to validate task names (which may contain invalid chars), at least not yet
            // ModelPath.validateName(name);

            // TODO - bust out path from creation action
            ModelNode node = this.node.addLink(creator.getPath().getName(), creator.getDescriptor(), creator.getPromise(), creator.getAdapter());
            modelGraph.add(node);
            notifyCreationListeners(node);
            bind(creator);
            return new NodeWrapper(node);
        }

        @Override
        public MutableModelNode addLink(String name, ModelRuleDescriptor descriptor, ModelPromise promise, ModelAdapter adapter) {
            // Disabled before 2.3 release due to not wanting to validate task names (which may contain invalid chars), at least not yet
            // ModelPath.validateName(name);

            ModelNode node = this.node.addLink(name, descriptor, promise, adapter);
            modelGraph.add(node);
            notifyCreationListeners(node);
            // TODO - don't create eagerly
            node.setState(ModelNode.State.Created);
            return new NodeWrapper(node);
        }

        @Override
        public void removeLink(String name) {
            remove(getPath().child(name));
        }

        @Override
        public void ensureCreated() {
            maybeCreate(node);
        }

        @Override
        public <T> T getPrivateData(ModelType<T> type) {
            return node.getPrivateData(type);
        }

        @Override
        public <T> void setPrivateData(ModelType<T> type, T object) {
            node.setPrivateData(type, object);
        }
    }

    private static class MutationKey {
        final ModelPath path;
        final MutationType type;

        public MutationKey(ModelPath path, MutationType type) {
            this.path = path;
            this.type = type;
        }

        @Override
        public boolean equals(Object obj) {
            MutationKey other = (MutationKey) obj;
            return path.equals(other.path) && type.equals(other.type);
        }

        @Override
        public int hashCode() {
            return path.hashCode() ^ type.hashCode();
        }
    }

    private static class BinderCreationListener implements ModelCreationListener {
        private final ModelRuleDescriptor descriptor;
        private final ModelReference<?> reference;
        private final boolean writable;
        private final Action<? super ModelPath> bindAction;
        private ModelPath boundTo;
        private ModelRuleDescriptor boundToCreator;

        public BinderCreationListener(ModelRuleDescriptor descriptor, ModelReference<?> reference, boolean writable, Action<? super ModelPath> bindAction) {
            this.descriptor = descriptor;
            this.reference = reference;
            this.writable = writable;
            this.bindAction = bindAction;
        }

        public boolean onCreate(ModelCreation creation) {
            ModelRuleDescriptor creatorDescriptor = creation.getDescriptor();
            ModelPath path = creation.getPath();
            ModelPromise promise = creation.getPromise();
            if (path.isTopLevel() && boundTo != null && isTypeCompatible(promise)) {
                throw new InvalidModelRuleException(descriptor, new ModelRuleBindingException(
                        new AmbiguousBindingReporter(reference, boundTo, boundToCreator, path, creatorDescriptor).asString()
                ));
            } else if (reference.getPath() == null && path.isTopLevel()) {
                boolean typeCompatible = isTypeCompatible(promise);
                if (typeCompatible) {
                    bindAction.execute(path);
                    boundTo = path;
                    boundToCreator = creatorDescriptor;
                    return false; // don't unregister listener, need to keep listening for other potential bindings
                }
            } else if (path.equals(reference.getPath())) {
                boolean typeCompatible = isTypeCompatible(promise);
                if (typeCompatible) {
                    bindAction.execute(path);
                    return true; // bound by type and path, stop listening
                } else {
                    throw new InvalidModelRuleException(descriptor, new ModelRuleBindingException(
                            IncompatibleTypeReferenceReporter.of(creatorDescriptor, promise, reference, writable).asString()
                    ));
                }
            }

            return false;
        }

        private boolean isTypeCompatible(ModelPromise promise) {
            return writable ? promise.canBeViewedAsWritable(reference.getType()) : promise.canBeViewedAsReadOnly(reference.getType());
        }
    }

}
