SHELL := /bin/sh

QA_RUN_ID ?= $(shell date -u +"%Y%m%dT%H%M%SZ")
QA_RESULTS_ROOT ?= $(CURDIR)/output/test-results
QA_RANDOM_SEED ?= 20260908
QA_PROFILE ?= smoke

export QA_RUN_ID
export QA_RESULTS_ROOT
export QA_RANDOM_SEED

.PHONY: \
	test-unit \
	test-integration \
	test-contract \
	test-e2e \
	test-e2e-real \
	test-frontend-stage11 \
	test-observability-stage12 \
	test-security \
	test-security-stage8 \
	test-ai-security \
	test-intelligence \
	test-chaos \
	test-performance \
	test-performance-smoke \
	test-performance-load \
	test-performance-stress \
	test-performance-spike \
	test-performance-soak \
	test-performance-sse \
	test-performance-idempotency \
	test-performance-race \
	test-performance-canary \
	test-functional-backend \
        test-contract-stage3 \
	test-all \
	test-env-up \
	test-env-down \
	test-env-reset \
	test-data-init \
	test-results-init

test-results-init:
	@./qa/scripts/prepare_test_results.sh

test-data-init: test-results-init
	@python3 ./qa/scripts/init_test_data.py \
		--report "$(QA_RESULTS_ROOT)/$(QA_RUN_ID)/json/fixture-index.json"

test-env-up:
	@./qa/scripts/test_environment.sh up

test-env-down:
	@./qa/scripts/test_environment.sh down

test-env-reset:
	@./qa/scripts/test_environment.sh reset

test-unit:
	@./qa/scripts/run_unit.sh

test-integration:
	@./qa/scripts/run_integration.sh

test-contract:
	@./qa/scripts/run_contract.sh

test-e2e:
	@./qa/scripts/run_e2e.sh

test-e2e-real:
	@./qa/scripts/run_e2e_real.sh

test-frontend-stage11:
	@./qa/scripts/run_stage11_frontend.sh

test-observability-stage12:
	@python3 qa/scripts/run_stage12.py --run "$(QA_RUN_ID)"

test-security:
	@./qa/scripts/run_security.sh

test-security-stage8:
	@services/ai-retrieval-service/.venv/bin/python qa/scripts/run_stage8_suite.py --run "$(QA_RUN_ID)"

test-ai-security:
	@services/ai-retrieval-service/.venv/bin/python qa/scripts/run_stage9.py --run "$(QA_RUN_ID)"

test-intelligence:
	@services/ai-retrieval-service/.venv/bin/python qa/scripts/run_stage10.py --run "$(QA_RUN_ID)"

test-chaos:
	@services/ai-retrieval-service/.venv/bin/python qa/scripts/run_stage7.py --require-complete

test-performance:
	@./qa/scripts/run_performance.sh "$(QA_PROFILE)"

test-performance-smoke:
	@./qa/scripts/run_performance.sh smoke

test-performance-load:
	@./qa/scripts/run_performance.sh load

test-performance-stress:
	@./qa/scripts/run_performance.sh stress

test-performance-spike:
	@./qa/scripts/run_performance.sh spike

test-performance-soak:
	@./qa/scripts/run_performance.sh soak

test-performance-sse:
	@./qa/scripts/run_performance.sh sse

test-performance-idempotency:
	@./qa/scripts/run_performance.sh idempotency

test-performance-race:
	@./qa/scripts/run_performance.sh race

test-performance-canary:
	@./qa/scripts/run_performance.sh canary

test-functional-backend:
	@./qa/scripts/run_backend_functional.sh

test-contract-stage3:
	@./qa/scripts/run_contract_stage3.sh

test-all: \
	test-unit \
	test-integration \
	test-contract \
	test-e2e \
	test-security \
	test-performance
