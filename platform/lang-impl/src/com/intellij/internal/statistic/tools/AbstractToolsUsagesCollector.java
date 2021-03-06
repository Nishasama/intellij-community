// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.internal.statistic.tools;

import com.intellij.codeInspection.InspectionEP;
import com.intellij.codeInspection.ex.InspectionToolWrapper;
import com.intellij.codeInspection.ex.ScopeToolState;
import com.intellij.ide.plugins.IdeaPluginDescriptor;
import com.intellij.ide.plugins.RepositoryHelper;
import com.intellij.internal.statistic.beans.UsageDescriptor;
import com.intellij.internal.statistic.service.fus.collectors.ProjectUsagesCollector;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.extensions.PluginDescriptor;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.project.DefaultProjectFactory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.ModificationTracker;
import com.intellij.profile.codeInspection.InspectionProjectProfileManager;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.util.ObjectUtils;
import one.util.streamex.StreamEx;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public abstract class AbstractToolsUsagesCollector extends ProjectUsagesCollector {

  private static final Predicate<ScopeToolState> BUNDLED = state -> {
    final IdeaPluginDescriptor descriptor = getIdeaPluginDescriptor(state);
    return descriptor != null && descriptor.isBundled();
  };

  private static final Predicate<ScopeToolState> LISTED = state -> {
    final IdeaPluginDescriptor descriptor = getIdeaPluginDescriptor(state);
    if (descriptor != null) {
      String path;
      try {
        //to avoid paths like this /home/kb/IDEA/bin/../config/plugins/APlugin
        path = descriptor.getPath().getCanonicalPath();
      }
      catch (final IOException e) {
        path = descriptor.getPath().getAbsolutePath();
      }
      if (path.startsWith(PathManager.getPluginsPath())) {
        final PluginId id = descriptor.getPluginId();
        if (id != null && getRepositoryPluginIds().contains(id.getIdString())) {
          return true;
        }
      }
    }
    return false;
  };

  private static IdeaPluginDescriptor getIdeaPluginDescriptor(final ScopeToolState state) {
    final InspectionEP extension = state.getTool().getExtension();
    return extension != null ? ObjectUtils.tryCast(extension.getPluginDescriptor(), IdeaPluginDescriptor.class) : null;
  }

  private static final Predicate<ScopeToolState> ENABLED = state -> !state.getTool().isEnabledByDefault() && state.isEnabled();

  private static final Predicate<ScopeToolState> DISABLED = state -> state.getTool().isEnabledByDefault() && !state.isEnabled();

  @NotNull
  @Override
  public Set<UsageDescriptor> getUsages(@NotNull final Project project) {
    final List<ScopeToolState> tools = InspectionProjectProfileManager.getInstance(project).getCurrentProfile().getAllTools();
    return filter(tools.stream())
      .map(ScopeToolState::getTool)
      .map(this::getInspectionToolId)
      .map(UsageDescriptor::new)
      .collect(Collectors.toSet());
  }

  @NotNull
  protected String getInspectionToolId(InspectionToolWrapper tool) {
    return tool.getLanguage() + "." + tool.getID();
  }

  @NotNull
  protected abstract Stream<ScopeToolState> filter(@NotNull final Stream<ScopeToolState> tools);

  protected static abstract class AbstractListedToolsUsagesCollector extends AbstractToolsUsagesCollector {
    @NotNull
    @Override
    protected String getInspectionToolId(InspectionToolWrapper tool) {
      return tool.getLanguage() + "." + tool.getExtension().getPluginId() + "." + tool.getID();
    }
  }

  public static class AllBundledToolsUsagesCollector extends AbstractToolsUsagesCollector {

    @NotNull
    @Override
    public String getGroupId() {
      return "statistics.all.bundled.tools";
    }

    @NotNull
    @Override
    protected Stream<ScopeToolState> filter(@NotNull final Stream<ScopeToolState> tools) {
      return tools.filter(BUNDLED);
    }
  }

  public static class AllListedToolsUsagesCollector extends AbstractListedToolsUsagesCollector {

    @NotNull
    @Override
    public String getGroupId() {
      return "statistics.all.listed.tools";
    }

    @NotNull
    @Override
    protected Stream<ScopeToolState> filter(@NotNull final Stream<ScopeToolState> tools) {
      return tools.filter(LISTED);
    }
  }

  public static class EnabledBundledToolsUsagesCollector extends AbstractToolsUsagesCollector {


    @NotNull
    @Override
    public String getGroupId() {
      return "statistics.enabled.bundled.tools";
    }

    @NotNull
    @Override
    protected Stream<ScopeToolState> filter(@NotNull final Stream<ScopeToolState> tools) {
      return tools.filter(ENABLED).filter(BUNDLED);
    }
  }

  public static class EnabledListedToolsUsagesCollector extends AbstractListedToolsUsagesCollector {

    @NotNull
    @Override
    public String getGroupId() {
      return "statistics.enabled.listed.tools";
    }

    @NotNull
    @Override
    protected Stream<ScopeToolState> filter(@NotNull final Stream<ScopeToolState> tools) {
      return tools.filter(ENABLED).filter(LISTED);
    }
  }

  public static class DisabledBundledToolsUsagesCollector extends AbstractToolsUsagesCollector {

    @NotNull
    @Override
    public String getGroupId() {
      return "statistics.disabled.bundled.tools";
    }

    @NotNull
    @Override
    protected Stream<ScopeToolState> filter(@NotNull final Stream<ScopeToolState> tools) {
      return tools.filter(DISABLED).filter(BUNDLED);
    }
  }

  public static class DisabledListedToolsUsagesCollector extends AbstractListedToolsUsagesCollector {

    @NotNull
    @Override
    public String getGroupId() {
      return "statistics.disabled.listed.tools";
    }

    @NotNull
    @Override
    protected Stream<ScopeToolState> filter(@NotNull final Stream<ScopeToolState> tools) {
      return tools.filter(DISABLED).filter(LISTED);
    }
  }

  private static Set<String> getRepositoryPluginIds() {
    final Project project = DefaultProjectFactory.getInstance().getDefaultProject();
    return CachedValuesManager.getManager(project).getCachedValue(project, () -> {
      List<IdeaPluginDescriptor> plugins = Collections.emptyList();
      try {
        final List<IdeaPluginDescriptor> cached = RepositoryHelper.loadCachedPlugins();
        if (cached != null) {
          plugins = cached;
        }
        else {
          // schedule plugins loading, will take them the next time
          ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
              RepositoryHelper.loadPlugins(null);
            }
            catch (final IOException ignored) {
            }
          });
        }
      }
      catch (final IOException ignored) {
      }
      final Set<String> ids = StreamEx.of(plugins).map(PluginDescriptor::getPluginId).nonNull().map(PluginId::getIdString).toSet();
      return CachedValueProvider.Result.create(ids, new DelayModificationTracker(1, TimeUnit.HOURS));
    });
  }

  private static class DelayModificationTracker implements ModificationTracker {

    private final long myStamp = System.currentTimeMillis();
    private final long myDelay;

    private DelayModificationTracker(final long delay, @NotNull final TimeUnit unit) {
      myDelay = TimeUnit.MILLISECONDS.convert(delay, unit);
    }

    @Override
    public long getModificationCount() {
      final long diff = System.currentTimeMillis() - (myStamp + myDelay);
      return diff > 0 ? diff : 0;
    }
  }
}