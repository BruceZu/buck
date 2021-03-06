/*
 * Copyright 2015-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.facebook.buck.android.relinker;

import com.facebook.buck.android.NdkCxxPlatform;
import com.facebook.buck.android.NdkCxxPlatforms.TargetCpuType;
import com.facebook.buck.graph.DirectedAcyclicGraph;
import com.facebook.buck.graph.TopologicalSort;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.model.Pair;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleDependencyVisitors;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicates;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * When linking shared libraries, by default, all symbols are exported from the library. In a
 * particular application, though, many of those symbols may never be used. Ideally, in each apk,
 * each shared library would only export the minimal set of symbols that are used by other libraries
 * in the apk. This would allow the linker to remove any dead code within the library (the linker
 * can strip all code that is unreachable from the set of exported symbols).
 * <p/>
 * The native relinker tries to remedy the situation. When enabled for an apk, the native relinker
 * will take the set of libraries in the apk and relink them in reverse order telling the linker to
 * only export those symbols that are referenced by a higher library.
 */
public class NativeRelinker {
  private final BuildRuleParams buildRuleParams;
  private final SourcePathResolver resolver;
  private final ImmutableMap<Pair<TargetCpuType, String>, SourcePath> relinkedLibs;
  private final ImmutableMap<Pair<TargetCpuType, String>, SourcePath> relinkedLibsAssets;
  private ImmutableMap<TargetCpuType, NdkCxxPlatform> nativePlatforms;
  private ImmutableList<RelinkerRule> rules;

  public NativeRelinker(
      BuildRuleParams buildRuleParams,
      SourcePathResolver resolver,
      ImmutableMap<TargetCpuType, NdkCxxPlatform> nativePlatforms,
      ImmutableMap<Pair<TargetCpuType, String>, SourcePath> linkableLibs,
      ImmutableMap<Pair<TargetCpuType, String>, SourcePath> linkableLibsAssets) {
    Preconditions.checkArgument(
        !linkableLibs.isEmpty() ||
            !linkableLibsAssets.isEmpty(),
        "There should be at least one native library to relink.");

    this.buildRuleParams = buildRuleParams;
    this.resolver = resolver;
    this.nativePlatforms = nativePlatforms;

    /*
    When relinking a library, any symbols needed by a (transitive) dependent must continue to be
    exported. As relinking one of those dependents may change the set of symbols that it needs,
    we only need to keep the symbols that are still used after a library is relinked. So, this
    relinking process basically works in the reverse order of the original link process. As each
    library is relinked, we now know the set of symbols that are needed in that library's
    dependencies.

    For linkables that can't be resolved to a BuildRule, we can't tell what libraries that one
    depends on. So, we essentially assume that everything depends on it.
    */

    ImmutableMap.Builder<BuildRule, Pair<TargetCpuType, SourcePath>> ruleMapBuilder =
        ImmutableMap.builder();
    ImmutableSet.Builder<Pair<TargetCpuType, SourcePath>> copiedLibraries = ImmutableSet.builder();

    for (Map.Entry<Pair<TargetCpuType, String>, SourcePath> entry :
        Iterables.concat(linkableLibs.entrySet(), linkableLibsAssets.entrySet())) {
      SourcePath source = entry.getValue();
      Optional<BuildRule> rule = resolver.getRule(source);
      if (rule.isPresent()) {
        ruleMapBuilder.put(rule.get(), new Pair<>(entry.getKey().getFirst(), source));
      } else {
        copiedLibraries.add(new Pair<>(entry.getKey().getFirst(), source));
      }
    }

    ImmutableMap<BuildRule, Pair<TargetCpuType, SourcePath>> ruleMap = ruleMapBuilder.build();
    ImmutableSet<BuildRule> linkableRules = ruleMap.keySet();

    // Now, for every linkable build rule, we need to figure out all the other linkable build rules
    // that could depend on it (or rather, could use symbols from it).

    // This is the sub-graph that includes the linkableRules and all the dependents (including
    // non-linkable rules).
    final DirectedAcyclicGraph<BuildRule> graph = getBuildGraph(linkableRules);
    ImmutableList<BuildRule> sortedRules =
        TopologicalSort.sort(graph, Predicates.<BuildRule>alwaysTrue());
    // This maps a build rule to every rule in linkableRules that depends on it. This (added to the
    // copied libraries) is the set of linkables that could use a symbol from this build rule.
    ImmutableMap<BuildRule, ImmutableSet<BuildRule>> allDependentsMap =
        getAllDependentsMap(linkableRules, graph, sortedRules);

    ImmutableMap.Builder<SourcePath, SourcePath> pathMap = ImmutableMap.builder();

    // Create the relinker rules for the libraries that couldn't be resolved back to a base rule.
    ImmutableList.Builder<RelinkerRule> relinkRules = ImmutableList.builder();
    for (Pair<TargetCpuType, SourcePath> p : copiedLibraries.build()) {
      // TODO(cjhopman): We shouldn't really need a full RelinkerRule at this point. We know that we
      // are just going to copy it, we could just leave these libraries in place and only calculate
      // the list of needed symbols.
      TargetCpuType cpuType = p.getFirst();
      SourcePath source = p.getSecond();
      RelinkerRule relink = makeRelinkerRule(cpuType, source, ImmutableList.<RelinkerRule>of());
      relinkRules.add(relink);
      pathMap.put(source, relink.getLibFileSourcePath());
    }
    ImmutableList<RelinkerRule> copiedLibrariesRules = relinkRules.build();

    // Process the remaining linkable rules in the reverse sorted order. This makes it easy to refer
    // to the RelinkerRules of dependents.
    Iterable<Pair<TargetCpuType, SourcePath>> sortedPaths =
        FluentIterable.from(sortedRules)
            .filter(Predicates.in(linkableRules))
            .transform(Functions.forMap(ruleMap))
            .toList()
            .reverse();
    Map<BuildRule, RelinkerRule> relinkerMap = new HashMap<>();

    for (Pair<TargetCpuType, SourcePath> p : sortedPaths) {
      TargetCpuType cpuType = p.getFirst();
      SourcePath source = p.getSecond();
      BuildRule baseRule = resolver.getRule(source).get();
      // Relinking this library must keep any of the symbols needed by the libraries from the rules
      // in relinkerDeps.
      ImmutableList<RelinkerRule> relinkerDeps =
          ImmutableList.<RelinkerRule>builder()
              .addAll(copiedLibrariesRules)
              .addAll(
                  Lists.transform(
                      ImmutableList.copyOf(allDependentsMap.get(baseRule)),
                      Functions.forMap(relinkerMap)))
              .build();

      RelinkerRule relink = makeRelinkerRule(cpuType, source, relinkerDeps);
      relinkRules.add(relink);
      pathMap.put(source, relink.getLibFileSourcePath());
      relinkerMap.put(baseRule, relink);
    }

    Function<SourcePath, SourcePath> pathMapper = Functions.forMap(pathMap.build());
    rules = relinkRules.build();
    relinkedLibs = ImmutableMap.copyOf(
        Maps.transformValues(linkableLibs, pathMapper));
    relinkedLibsAssets = ImmutableMap.copyOf(
        Maps.transformValues(linkableLibsAssets, pathMapper));
  }

  private static DirectedAcyclicGraph<BuildRule> getBuildGraph(Set<BuildRule> rules) {
    // TODO(cjhopman): can this use .in(rules) instead of alwaysTrue()?
    return BuildRuleDependencyVisitors.getBuildRuleDirectedGraphFilteredBy(
        rules, Predicates.alwaysTrue(), Predicates.alwaysTrue());
  }

  /**
   * Creates a map from every BuildRule to the set of transitive dependents of that BuildRule that
   * are in the linkableRules set.
   */
  private ImmutableMap<BuildRule, ImmutableSet<BuildRule>> getAllDependentsMap(
      Set<BuildRule> linkableRules,
      DirectedAcyclicGraph<BuildRule> graph,
      ImmutableList<BuildRule> sortedRules) {
    final Map<BuildRule, ImmutableSet<BuildRule>> allDependentsMap = new HashMap<>();
    // Using the sorted list of rules makes this calculation much simpler. We can just assume that
    // we already know all the dependents of a rules incoming nodes when we are processing that
    // rule.
    for (BuildRule rule : sortedRules.reverse()) {
      ImmutableSet.Builder<BuildRule> transitiveDependents = ImmutableSet.builder();
      for (BuildRule dependent : graph.getIncomingNodesFor(rule)) {
        transitiveDependents.addAll(allDependentsMap.get(dependent));
        if (linkableRules.contains(dependent)) {
          transitiveDependents.add(dependent);
        }
      }
      allDependentsMap.put(rule, transitiveDependents.build());
    }
    return ImmutableMap.copyOf(allDependentsMap);
  }

  private RelinkerRule makeRelinkerRule(
      TargetCpuType cpuType,
      SourcePath source,
      ImmutableList<RelinkerRule> relinkerDeps) {
    Function<RelinkerRule, SourcePath> getSymbolsNeeded = new Function<RelinkerRule, SourcePath>() {
      @Override
      public SourcePath apply(RelinkerRule rule) {
        return rule.getSymbolsNeededPath();
      }
    };
    String libname = resolver.getAbsolutePath(source).getFileName().toString();
    return new RelinkerRule(
        buildRuleParams
            .withFlavor(ImmutableFlavor.of("xdso-dce"))
            .withFlavor(ImmutableFlavor.of(Flavor.replaceInvalidCharacters(cpuType.toString())))
            .withFlavor(ImmutableFlavor.of(Flavor.replaceInvalidCharacters(libname)))
            .appendExtraDeps(relinkerDeps),
        resolver,
        cpuType,
        source,
        nativePlatforms.get(cpuType),
        resolver.getRule(source).orNull(),
        ImmutableList.copyOf(Lists.transform(relinkerDeps, getSymbolsNeeded))
    );
  }

  public ImmutableMap<Pair<TargetCpuType, String>, SourcePath> getRelinkedLibs() {
    return relinkedLibs;
  }

  public ImmutableMap<Pair<TargetCpuType, String>, SourcePath>
  getRelinkedLibsAssets() {
    return relinkedLibsAssets;
  }

  public ImmutableList<RelinkerRule> getRules() {
    return rules;
  }
}
