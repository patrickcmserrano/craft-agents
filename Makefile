JAVAC ?= javac --release 21
JAR ?= jar
JAVA ?= java
CLOJURE ?= clojure

BUILD_DIR = target/classes
AGENT_JAR = target/craft-agents.jar
STUDIO_PORT ?= 8095
NREPL_PORT ?= 7890

JAVA_SOURCES = $(shell find src/java -name "*.java" | sort)
# Arquivo de classpath PROPRIO do craft-agents. NAO use /tmp/clojure_jars.txt:
# esse caminho e do craft-repl (CraftAgent.java e ClojureBootstrapperAgent.java o
# consomem), e sobrescreve-lo trocava o src/clj do craft-repl pelo daqui,
# quebrando o nREPL 7888 daquele repo.
CLOJURE_JARS_LIST = /tmp/craft-agents-classpath.txt

.PHONY: all compile-java test test-epic tdd-status jar attach studio clean classpath-list

all: compile-java

classpath-list: compile-java
	@$(CLOJURE) -Spath | tr ':' '\n' | awk 'BEGIN{dir="/home/pace/dev/minecraft/craft-agents"} {if ($$0 ~ /^\//) print; else print dir "/" $$0}' > $(CLOJURE_JARS_LIST)
	@echo "[+] Lista de classpath para o nREPL in-server em $(CLOJURE_JARS_LIST) ($$(wc -l < $(CLOJURE_JARS_LIST)) entradas)"

compile-java:
	@mkdir -p $(BUILD_DIR)
	$(JAVAC) -d $(BUILD_DIR) $(JAVA_SOURCES)
	@echo "[+] Java 21 compilado em $(BUILD_DIR)"

studio: compile-java
	@echo "[+] Iniciando Agent Studio na porta $(STUDIO_PORT)..."
	$(CLOJURE) -M -m craft.agents.studio.server $(STUDIO_PORT)

test: compile-java
	$(CLOJURE) -M:test -m craft.agents.test-runner

test-epic: compile-java
	$(CLOJURE) -M:test -m craft.agents.test-runner --epic $(EPIC)

tdd-status: compile-java
	$(CLOJURE) -M:test -m craft.agents.test-runner --summary-only

jar: compile-java
	@mkdir -p target
	@printf "Manifest-Version: 1.0\nAgent-Class: com.craft.agents.attach.BridgeReloadAgent\nCan-Redefine-Classes: true\nCan-Retransform-Classes: true\n" > target/MANIFEST.MF
	$(JAR) cmf target/MANIFEST.MF $(AGENT_JAR) -C $(BUILD_DIR) . -C resources .
	@echo "[+] Artefato gerado em $(AGENT_JAR)"

attach: jar classpath-list
	@PID_RESOLVED=$$( [ -n "$(PID)" ] && echo "$(PID)" || pgrep -f "[s]erver.jar nogui" | head -n 1 ); \
	if [ -z "$$PID_RESOLVED" ]; then PID_RESOLVED=$$(pgrep -f "[f]abric-server-launcher" | head -n 1); fi; \
	if [ -z "$$PID_RESOLVED" ]; then PID_RESOLVED=$$(pgrep -f "[K]notServer" | head -n 1); fi; \
	if [ -z "$$PID_RESOLVED" ]; then \
		echo "[-] Erro: Servidor Minecraft nao detectado em execucao!"; \
		exit 1; \
	fi; \
	echo "[+] Anexando craft-agents ao PID $$PID_RESOLVED (nREPL in-server :$(NREPL_PORT))..."; \
	TMP_JAR="target/craft-agents-attach-$$$$.jar"; \
	cp $(AGENT_JAR) "$$TMP_JAR"; \
	$(JAVA) -Dcraft.agents.nrepl.port=$(NREPL_PORT) -cp $(BUILD_DIR) com.craft.agents.attach.AttachCli $$PID_RESOLVED "$$TMP_JAR" $(STUDIO_PORT)

clean:
	rm -rf target .cpcache
