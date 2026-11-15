.PHONY: all check test parity swift-lint android-test clean

all: check

check: test parity swift-lint android-test
	@echo "==================================================="
	@echo " [PASS] ALL QUALITY GATES VERIFIED SUCCESSFULLY!"
	@echo "==================================================="

test:
	python -m unittest discover -s tests -p "test_*.py"

parity:
	python scripts/test_parity.py

swift-lint:
	python scripts/validate_swift.py

android-test:
	sh ./gradlew :app:testDebugUnitTest

clean:
	find . -type d -name "__pycache__" -exec rm -rf {} + 2>/dev/null || true
	find . -type d -name ".pytest_cache" -exec rm -rf {} + 2>/dev/null || true
	find . -type d -name ".mypy_cache" -exec rm -rf {} + 2>/dev/null || true
