/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */

package org.mule.functional.junit4;

import org.mule.functional.junit4.runners.ArtifactClassLoaderRunnerConfig;

/**
 * Base class for mule functional test cases that run the test using class loading isolation. This class will set the default
 * values for testing mule components.
 * <p/>
 * The artifacts that are going to be ALWAYS part of the container should be excluded from application and plugin
 * {@link org.mule.runtime.module.artifact.classloader.ArtifactClassLoader}. Therefore the
 * {@link ArtifactClassLoaderRunnerConfig#applicationArtifactExclusionsDependencyFilter()} is set here with the list of
 * groupIds for those modules.
 * <p/>
 * Whenever a new groupId is created for mule components that would be always added to the container they must be added here.
 *
 * @since 4.0
 */
@ArtifactClassLoaderRunnerConfig(muleContainerCoordinates = "org.mule.distributions:mule-standalone:pom:4.0-SNAPSHOT",
    muleContainerExclusions = {"org.mule.tests:*:*:*", "org.mule.extensions:mule-extensions-all:*:*"},
    //TODO simplify this as a general exclusion rule for junit, check why hamcrest has to be excluded too
    muleContainerExclusionsDependencyFilter = {"junit:*:*:*", "org.hamcrest:*:*:*"},
    applicationArtifactExclusionsDependencyFilter = {"org.mule:*:*:*", "org.mule.modules*:*:*:*", "org.mule.transports:*:*:*",
        "org.mule.mvel:*:*:*", "org.mule.common:*:*:*", "org.mule.extensions:*:*:*"})
public abstract class MuleArtifactFunctionalTestCase extends ArtifactFunctionalTestCase {

}