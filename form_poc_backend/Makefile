GRADLEW := ./gradlew

.DEFAULT_GOAL := help

.PHONY: help dev test test-one check format build clean

help: ## Show available targets
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) \
		| awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-12s\033[0m %s\n", $$1, $$2}'

dev: ## Run with live reload on :9100 (Dev Services starts Mongo)
	$(GRADLEW) quarkusDev

test: ## Run unit tests — no Docker required
	$(GRADLEW) test

test-one: ## Run a single test class, e.g. make test-one TEST=ExpressionEvaluatorTest
	$(GRADLEW) test --tests "$(TEST)"

check: ## Format check + tests
	$(GRADLEW) spotlessCheck test

format: ## Apply formatting
	$(GRADLEW) spotlessApply

build: ## Build the runnable jar
	$(GRADLEW) build -x test

clean: ## Remove build output
	$(GRADLEW) clean
