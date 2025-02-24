/*
 * Copyright (C) 2025 Square, Inc.
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

package app.cash.licensee.internal

import app.cash.licensee.LicenseeExtension
import app.cash.licensee.LicenseeExtension.AllowDependencyOptions
import app.cash.licensee.LicenseeExtension.IgnoreDependencyOptions
import app.cash.licensee.SpdxId
import app.cash.licensee.UnusedAction
import app.cash.licensee.ViolationAction
import java.util.*
import org.gradle.api.Action
import org.gradle.api.Named
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.artifacts.Dependency
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.provider.SetProperty


internal abstract class IgnoredCoordinate : Named {
  abstract val ignoredDatas: MapProperty<String, IgnoredData>
}

internal abstract class MutableLicenseeExtension : LicenseeExtension {
  internal abstract val allowedIdentifiers: SetProperty<String>
  internal abstract val allowedUrls: MapProperty<String, Optional<String>>
  internal abstract val allowedDependencies: MapProperty<DependencyCoordinates, Optional<String>>
  internal abstract val ignoredGroupIds: MapProperty<String, IgnoredData>
  internal abstract val ignoredCoordinates: NamedDomainObjectContainer<IgnoredCoordinate>
  internal abstract val violationAction: Property<ViolationAction>
  internal abstract val unusedAction: Property<UnusedAction>

  init {
    violationAction.convention(ViolationAction.FAIL)
    unusedAction.convention(UnusedAction.LOG)
  }

  fun toDependencyTreeConfig(): Provider<DependencyConfig> {
    return ignoredGroupIds.map { ignoredGroupIds ->
      DependencyConfig(
        ignoredGroupIds.toMap(),
        ignoredCoordinates.groupBy({ it.name }) {
          it.ignoredDatas.get()
        }.mapValues {
          it.value.single()
        },
      )
    }
  }

  fun toLicenseValidationConfig(): Provider<ValidationConfig> {
    return allowedIdentifiers.zip(allowedUrls, allowedDependencies) { allowedIdentifiers, allowedUrls, allowedDependencies ->
      ValidationConfig(
        allowedIdentifiers,
        allowedUrls.mapValues {
          it.value.orElse(null)
        },
        allowedDependencies.mapValues {
          it.value.orElse(null)
        },
      )
    }
  }

  override fun allow(spdxId: String) {
    requireNotNull(SpdxId.findByIdentifier(spdxId)) {
      "$spdxId is not a valid SPDX id."
    }
    allowedIdentifiers.add(spdxId)
  }

  override fun allowUrl(url: String, options: Action<LicenseeExtension.AllowUrlOptions>) {
    val option = object : LicenseeExtension.AllowUrlOptions {
      var setReason: String? = null
      override fun because(reason: String) {
        setReason = reason
      }
    }
    options.execute(option)
    allowedUrls.put(url, Optional.ofNullable(option.setReason))
  }

  override fun allowDependency(
    groupId: String,
    artifactId: String,
    version: String,
    options: Action<AllowDependencyOptions>,
  ) {
    val optionsImpl = AllowDependencyOptionsImpl()
    options.execute(optionsImpl)
    allowedDependencies.put(DependencyCoordinates(group = groupId, artifact = artifactId, version = version), Optional.ofNullable(optionsImpl.setReason))
  }

  private class AllowDependencyOptionsImpl : AllowDependencyOptions {
    var setReason: String? = null
    override fun because(reason: String) {
      setReason = reason
    }
  }

  override fun allowDependency(
    dependencyProvider: Provider<out Dependency>,
    options: Action<AllowDependencyOptions>,
  ) {
    val optionsImpl = AllowDependencyOptionsImpl()
    options.execute(optionsImpl)

    allowedDependencies.putAll(
      dependencyProvider.map {
        mapOf(
          DependencyCoordinates(
            group = requireNotNull(it.group) { "group was null in allowDependency for ${it.name}" },
            artifact = it.name,
            version = requireNotNull(it.version) { "version was null in allowDependency for ${it.name}" },
          ) to Optional.ofNullable(optionsImpl.setReason),
        )
      },
    )
  }

  override fun ignoreDependencies(groupId: String, artifactId: String?, options: Action<IgnoreDependencyOptions>) {
    val option = object : IgnoreDependencyOptions {
      var setReason: String? = null
      override fun because(reason: String) {
        setReason = reason
      }

      override var transitive: Boolean = false
    }

    options.execute(option)
    if (option.transitive && option.setReason == null) {
      throw RuntimeException(
        buildString {
          append("Transitive dependency ignore on '")
          append(groupId)
          if (artifactId != null) {
            append(':')
            append(artifactId)
          }
          append("' is dangerous and requires a reason string")
        },
      )
    }
    val ignoredData = IgnoredData(option.setReason, option.transitive)
    if (artifactId == null) {
      ignoredGroupIds.put(groupId, ignoredData)
    } else {
      ignoredCoordinates.configure(groupId) {
        it.ignoredDatas.put(artifactId, ignoredData)
      }
    }
  }

  override fun violationAction(level: ViolationAction) {
    violationAction.set(level)
  }

  override fun unusedAction(level: UnusedAction) {
    unusedAction.set(level)
  }
}

private fun <T, L, R, V> Provider<T>.zip(left: Provider<L>, right: Provider<R>, merge: (T, L, R) -> V): Provider<V> {
  return zip(left) { t, l ->
    t to l
  }.zip(right) { (t, l), r ->
    merge(t, l, r)
  }
}

private fun <T> NamedDomainObjectContainer<T>.configure(name: String, config: Action<T>) {
  if (name in names) {
    named(name, config)
  } else {
    register(name, config)
  }
}
