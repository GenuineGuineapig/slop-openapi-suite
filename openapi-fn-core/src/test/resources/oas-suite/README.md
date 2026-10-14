# OpenAPI Specification schema tests

Copied unchanged from the OpenAPI Initiative's
[OpenAPI-Specification](https://github.com/OAI/OpenAPI-Specification) repository
(`tests/schema/pass` and `tests/schema/fail` of the `v3.1-dev` and `v3.2-dev` branches), licensed
under the Apache License 2.0 (see `LICENSE`). `OpenApiConformanceSuiteTest` loads every `pass`
description (which must load without errors) and every `fail` description (which must be rejected).
