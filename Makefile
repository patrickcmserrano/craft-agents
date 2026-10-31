JAVAC ?= javac --release 21
JAR ?= jar
JAVA ?= java
CLOJURE ?= clojure

BUILD_DIR = target/classes
AGENT_JAR = target/craft-agents.jar
STUDIO_PORT ?= 8095
NREPL_PORT ?= 7890

JAVA_SOURCES = $(shell find src/java -name "*.java" | sort)

.PHONY: all compile-java test test-epic tdd-status jar attach clean

all: compile-java

compile-java:
	@mkdir -p $(BUILD_DIR)
	$(JAVAC) -d $(BUILD_DIR) $(JAVA_SOURCES)
	@echo "[+] Java 21 compilado em $(BUILD_DIR)"

test: compile-java
	$(CLOJURE) -M:test -m craft.agents.test-runner

test-epic: compile-java
	$(CLOJURE) -M:test -m craft.agents.test-runner --epic $(EPIC)

tdd-status: compile-java
	$(CLOJURE) -M:test -m craft.agents.test-runner --summary-only

jar: compile-java
	@mkdir -p target
	@printf "Manifest-Version: 1.0\nAgent-Class: com.craft.agents.attach.CraftAgentsBootstrap\nCan-Redefine-Classes: true\nCan-Retransform-Classes: true\n" > target/MANIFEST.MF
	$(JAR) cmf target/MANIFEST.MF $(AGENT_JAR) -C $(BUILD_DIR) . -C resources .
	@echo "[+] Artefato gerado em $(AGENT_JAR)"

attach: jar
	@PID_RESOLVED=$$( [ -n "$(PID)" ] && echo "$(PID)" || pgrep -f "fabric-server-launcher" | head -n 1 ); \
	if [ -z "$$PID_RESOLVED" ]; then PID_RESOLVED=$$(pgrep -f "KnotServer" | head -n 1); fi; \
	if [ -z "$$PID_RESOLVED" ]; then PID_RESOLVED=$$(pgrep -f "KnotClient" | head -n 1); fi; \
	if [ -z "$$PID_RESOLVED" ]; then \
		echo "[-] Erro: Minecraft nao detectado em execucao!"; \
		exit 1; \
	fi; \
	echo "[+] Anexando craft-agents ao PID $$PID_RESOLVED..."; \
	$(JAVA) -cp $(BUILD_DIR) com.craft.agents.attach.AttachCli $$PID_RESOLVED $(AGENT_JAR) $(STUDIO_PORT)

clean:
	rm -rf target .cpcache
