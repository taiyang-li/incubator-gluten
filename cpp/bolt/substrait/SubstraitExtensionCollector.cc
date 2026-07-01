/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "SubstraitExtensionCollector.h"

#include "BoltSubstraitSignature.h"

namespace gluten {

int SubstraitExtensionCollector::getReferenceNumber(
    const std::string& functionName,
    const std::vector<TypePtr>& arguments) {
  const auto& substraitFunctionSignature = BoltSubstraitSignature::toSubstraitSignature(functionName, arguments);
  // TODO: Currently we treat all bolt registry based function signatures as
  // custom substrait extension, so no uri link and leave it as empty.
  return extensionRegistry_->getReferenceNumber({"", substraitFunctionSignature});
}

void SubstraitExtensionCollector::addExtensionsToPlan(::substrait::Plan* plan) const {
  extensionRegistry_->addExtensionsToPlan(plan);
}

SubstraitExtensionCollector::SubstraitExtensionCollector() {
  extensionRegistry_ = std::make_shared<SubstraitExtensionRegistry>();
}

} // namespace gluten
