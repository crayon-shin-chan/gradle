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
package org.gradle.api.publish.ivy
import org.gradle.integtests.fixtures.CrossVersionIntegrationSpec
import org.gradle.integtests.fixtures.TargetVersions
import org.gradle.test.fixtures.file.TestFile
import org.gradle.test.fixtures.ivy.IvyFileRepository
import org.gradle.util.GradleVersion

@TargetVersions('0.9+')
class IvyPublishCrossVersionIntegrationTest extends CrossVersionIntegrationSpec {

    final TestFile repoDir = file("ivy-repo")
    final IvyFileRepository repo = new IvyFileRepository(repoDir)

    def "ivy java publication generated by ivy-publish plugin can be consumed by previous versions of Gradle"() {
        given:
        projectPublishedUsingMavenPublishPlugin('java')

        expect:
        consumePublicationWithPreviousVersion('')

        file('build/resolved').assertHasDescendants("published-${publishedVersion}.jar", 'commons-collections-3.0.jar')
    }

    def "ivy war publication generated by ivy-publish plugin can be consumed by previous versions of Gradle"() {
        given:
        projectPublishedUsingMavenPublishPlugin('web')

        expect:
        consumePublicationWithPreviousVersion('@war')

        file('build/resolved').assertHasDescendants("published-${publishedVersion}.war")
    }

    def projectPublishedUsingMavenPublishPlugin(def componentToPublish) {
        settingsFile.text = "rootProject.name = 'published'"

        buildFile.text = """
apply plugin: 'war'
apply plugin: 'ivy-publish'

group = 'org.gradle.crossversion'
version = '${publishedVersion}'

repositories {
    mavenCentral()
}
dependencies {
    compile "commons-collections:commons-collections:3.0"
}
publishing {
    repositories {
        ivy { url "${repo.uri}" }
    }
    publications {
        ivy(IvyPublication) {
            from components['${componentToPublish}']
        }
    }
}
"""

        version current withTasks 'publish' run()
    }

    def consumePublicationWithPreviousVersion(def artifact) {
        settingsFile.text = "rootProject.name = 'consumer'"

        def repositoryDefinition
        if (supportsIvyRepositoryWithSpaceInArtifactPattern()) {
            repositoryDefinition = """
                ivy {
                    url "${repo.uri}"
                }
"""
        } else {
            repositoryDefinition = """
                println "Adding resolver directly due to no 'ivy' repository support"
                add(new org.apache.ivy.plugins.resolver.FileSystemResolver()) {
                    name = 'repo'
                    addIvyPattern("${repoDir.absolutePath}/[organisation]/[module]/[revision]/ivy-[revision].xml")
                    addArtifactPattern("${repoDir.absolutePath}/[organisation]/[module]/[revision]/[artifact]-[revision].[ext]")
                    descriptor = 'required'
                    checkmodified = true
                }
"""
        }

        buildFile.text = """
configurations {
    lib
}
repositories {
    mavenCentral()

    $repositoryDefinition
}
dependencies {
    lib 'org.gradle.crossversion:published:${publishedVersion}${artifact}'
}
task retrieve(type: Sync) {
    into 'build/resolved'
    from configurations.lib
}
"""

        version previous withDeprecationChecksDisabled() withTasks 'retrieve' run()
    }

    private boolean supportsIvyRepositoryWithSpaceInArtifactPattern() {
        // IvyArtifactRepository was introduced in milestone-3, but didn't support spaces in uri until milestone-7
        return previous.version.compareTo(GradleVersion.version("1.0-milestone-7")) >= 0
    }

    def getPublishedVersion() {
        "1.9"
    }
}
