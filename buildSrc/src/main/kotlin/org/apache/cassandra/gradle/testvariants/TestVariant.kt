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
package org.apache.cassandra.gradle.testvariants

import org.gradle.api.Action
import org.gradle.api.tasks.testing.Test
import java.util.*
import javax.inject.Inject

@Suppress("unused")
open class TestVariant @Inject constructor(val name: String) {
    var sourceConfigFiles: List<Any> = ArrayList()
    var cassandraYaml: Any = "test/cassandra.$name.yaml"

    private val configActions: MutableList<Action<Test>> = ArrayList()

    fun configure(configAction: Action<Test>) {
        configActions.add(configAction)
    }

    fun configActions(): List<Action<Test>> {
        return configActions
    }

}