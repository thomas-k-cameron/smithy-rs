/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.client.smithy.endpoint

import software.amazon.smithy.model.Model
import software.amazon.smithy.model.knowledge.KnowledgeIndex
import software.amazon.smithy.model.shapes.ServiceShape
import software.amazon.smithy.rulesengine.language.EndpointRuleSet
import software.amazon.smithy.rulesengine.traits.EndpointRuleSetTrait
import software.amazon.smithy.rulesengine.traits.EndpointTestsTrait
import software.amazon.smithy.rust.codegen.core.util.getTrait
import java.util.concurrent.ConcurrentHashMap

/**
 * Index to ensure that endpoint rulesets are parsed only once
 */
class EndpointRulesetIndex : KnowledgeIndex {

    private val ruleSets: ConcurrentHashMap<ServiceShape, EndpointRuleSet?> = ConcurrentHashMap()

    fun endpointRulesForService(serviceShape: ServiceShape) = ruleSets.computeIfAbsent(
        serviceShape,
    ) {
        serviceShape.getTrait<EndpointRuleSetTrait>()?.ruleSet?.let { EndpointRuleSet.fromNode(it) }
            ?.also { it.typeCheck() }
    }

    fun endpointTests(serviceShape: ServiceShape) =
        serviceShape.getTrait<EndpointTestsTrait>()?.testCases ?: emptyList()

    companion object {
        fun of(model: Model): EndpointRulesetIndex {
            return model.getKnowledge(EndpointRulesetIndex::class.java) { EndpointRulesetIndex() }
        }
    }
}
