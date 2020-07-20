/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.cassandra.gradle.distarchives

import org.apache.cassandra.gradle.configureEach
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.distribution.DistributionContainer
import org.gradle.api.distribution.plugins.DistributionPlugin
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.api.tasks.bundling.Compression
import org.gradle.api.tasks.bundling.Tar
import org.gradle.kotlin.dsl.*
import java.io.File
import java.security.MessageDigest

@Suppress("unused")
class DistArchivesPlugin : Plugin<Project> {
    override fun apply(project: Project): Unit = project.run {
        plugins.apply(DistributionPlugin::class)

        extensions.getByType<DistributionContainer>().configureEach {
            if (name == "main")
                configureDistTasks(project, "bin", "distTar", "distZip")
            else
                configureDistTasks(project, name, "${name}DistTar", "${name}DistZip")
        }
    }

    private fun configureDistTasks(project: Project, classifier: String, tarTask: String, zipTask: String) = project.run {
        tasks.configureEach<AbstractArchiveTask>(tarTask, zipTask) {
            archiveClassifier.set(classifier)

            destinationDirectory.set(project.buildDir)

            // Need to do some "name mangling" here, as the default archive file name generated by the 'distribution'
            // plugin is different from what C* generates.
            archiveBaseName.set(project.name)
            // Also the top-level directory in the tarballs needs to be tweaked
            includeEmptyDirs = false
            eachFile {
                path = "${archiveBaseName.get()}-${project.version}/${path.substringAfter('/')}"
            }

            doLast {
                val file = archiveFile.get().asFile
                val bytes = file.readBytes() // tar.gz files are ~50MB - so it's okay-ish to read them into a byte-array and not do the file-I/O dance
                listOf("SHA-256" to "sha256", "SHA-512" to "sha512").forEach { (digest, ext) ->
                    val md = MessageDigest.getInstance(digest)
                    md.update(bytes)
                    @Suppress("EXPERIMENTAL_API_USAGE") val hex = md.digest().asUByteArray().joinToString("") { b -> b.toString(16).padStart(2, '0') }
                    File(file.parentFile, "${file.name}.$ext").writeText("$hex\n")
                }
            }

            doFirst {
                if (JavaVersion.current().isJava11Compatible) {
                    logger.warn("Class files from this build require Java 11 or newer and will not work with Java 8. It is recommended to generate distributions using Java 8.")
                }
            }
        }

        tasks.named<Tar>(tarTask) {
            compression = Compression.GZIP
            archiveExtension.set("tar.gz")
        }

        if (classifier == "bin") {
            tasks.register<Sync>("dist") {
                destinationDir = project.buildDir.resolve("dist")

                group = "distribution"
                description = "Unpacks the tarball into $destinationDir"

                val distTar by project.tasks.existing(Tar::class)
                dependsOn(distTar)
                from({ project.tarTree(distTar.get().archiveFile.get().asFile) })
                includeEmptyDirs = false
                eachFile {
                    path = path.substringAfter('/')
                }
            }
        }
    }
}