/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.idea.nodejs.protractor

import com.intellij.execution.configuration.EnvironmentVariablesData
import com.intellij.javascript.nodejs.interpreter.NodeJsInterpreterRef
import com.intellij.openapi.util.JDOMExternalizerUtil
import com.intellij.openapi.util.io.FileUtil
import org.jdom.Element

data class KotlinProtractorRunSettings(
        val interpreterRef: NodeJsInterpreterRef = NodeJsInterpreterRef.createProjectRef(),
        private val configFilePath: String = "",
        private val testFilePath: String = "",
        val seleniumAddress: String = "",
        val extraOptions: String = "",
        val envData: EnvironmentVariablesData = EnvironmentVariablesData.DEFAULT
) {
    val configFileSystemDependentPath get() = FileUtil.toSystemDependentName(configFilePath)
    val testFileSystemDependentPath get() = FileUtil.toSystemDependentName(testFilePath)

    fun writeToXML(element: Element) {
        JDOMExternalizerUtil.addElementWithValueAttribute(element, "config-file", FileUtil.toSystemIndependentName(configFileSystemDependentPath))
        JDOMExternalizerUtil.addElementWithValueAttribute(element, "test-file", FileUtil.toSystemIndependentName(testFileSystemDependentPath))
        JDOMExternalizerUtil.addElementWithValueAttribute(element, "selenium-address", seleniumAddress)
        JDOMExternalizerUtil.addElementWithValueAttribute(element, "extra-protractor-options", extraOptions)
        JDOMExternalizerUtil.addElementWithValueAttribute(element, "node-interpreter", interpreterRef.referenceName)
        envData.writeExternal(element)
    }

    companion object {
        fun readFromXML(element: Element): KotlinProtractorRunSettings {
            val configFilePath = JDOMExternalizerUtil.getFirstChildValueAttribute(element, "config-file")
            val testFilePath = JDOMExternalizerUtil.getFirstChildValueAttribute(element, "test-file")
            val seleniumAddress = JDOMExternalizerUtil.getFirstChildValueAttribute(element, "selenium-address")
            val extraOptions = JDOMExternalizerUtil.getFirstChildValueAttribute(element, "extra-protractor-options")
            val interpreterRefName = JDOMExternalizerUtil.getFirstChildValueAttribute(element, "node-interpreter")
            val envData = EnvironmentVariablesData.readExternal(element)
            return KotlinProtractorRunSettings(
                    NodeJsInterpreterRef.create(interpreterRefName),
                    configFilePath ?: "",
                    testFilePath ?: "",
                    seleniumAddress ?: "",
                    extraOptions ?: "",
                    envData
            )
        }
    }
}