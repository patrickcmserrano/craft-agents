# Roteiro de Vídeo — Por Que Estamos Substituindo o Mineflayer: A Engenharia do `craft-agents`

> **Projeto:** `craft-agents` — Next-Gen Embodied AI & Autonomous Agent Engine for Minecraft
> **Formato Principal:** Vídeo Horizontal 16:9 (YouTube Deep-Dive de Engenharia — **11 a 13 minutos**)
> **Bônus Incluso (Seção 5):** Corte Vertical 9:16 (**60 segundos** — Reels / Shorts / TikTok)
> **Tom e Estilo:** Engenharia de software real, narrativa investigativa (começando por um bug cômico/real no próprio servidor até abrir o código-fonte do mercado), didático, rítmico e sem hype vazio.
> **Função Narrativa:** Apresentar o panorama atual de bots e IA no Minecraft, provar no código por que a stack dominante de 2011 bateu no teto, e revelar as **5 Ideias-Chave** da arquitetura do `craft-agents`.

---

## Opções de Título e Thumbnail (YouTube 16:9)

1. **Opção A (Forte em Engenharia + IA):** *"Abri o Código do Mineflayer — E Decidi Construir um Motor de Agentes do Zero"*
2. **Opção B (Provocativa / Performance):** *"200 MB por Bot em JavaScript?! Construindo um Motor Next-Gen de IA no Minecraft"*
3. **Opção C (Foco em Arquitetura Cognitiva):** *"Como Agentes de IA Realmente Deveriam Funcionar em Jogos (Adeus, Mineflayer)"*

- **Ideia Visual de Thumbnail:** Divisão ao meio. Esquerda em tom vermelho/caótico: logo JS + `"210 MB RAM / bot"` + Karlach presa atrás de um portão com erro `Chunk size is 42...`. Direita em tom ciano/limpo: Grafo de Behavior Tree iluminado + `"< 500 KB / Zero-Copy JVM"`. Texto curto: **"ADEUS, CALLBACK HELL"**.

---

## 1. Tese Central do Vídeo

- **Em uma frase:** Os laboratórios de IA mais avançados do planeta estão construindo mentes do futuro em cima de uma arquitetura mutável de JavaScript de 2011 que não aguenta mods, desperdiça gigabytes de RAM e coloca LLMs lentas no volante via `eval()` — e dá para resolver tudo isso com engenharia de sistemas de verdade.
- **O que o espectador leva embora:** Entendimento claro de como funcionam protocolos de jogos, pathfinding 3D, Behavior Trees, arbitragem de concorrência por *leases* e a separação correta entre controle motor em tempo real (`50ms`) e raciocínio de LLM (`System 1` vs `System 2`).

---

## 2. Roteiro Falado Completo (YouTube 16:9 — ~12 min)

Marcações de direção:
- `[pausa]` = meio segundo de respiro dramático
- `[tela]` = momento exato em que entra evidência/diagrama na edição
- **negrito** = ênfase vocal

---

### ATO I — O GANCHO: A GUARDIÃ PRESA NA JAULA `[00:00 — 01:25]`

> Tudo começou porque a Karlach ficou presa dentro do cercado das capivaras. `[pausa]`
>
> Sério. Eu tenho um servidor de Minecraft 1.21 rodando em Linux, cheio de mods, onde eu já tinha construído o **craft-repl** — um agente que injeta Clojure ao vivo dentro da JVM do jogo sem precisar reiniciar nada.
>
> Só que, para colocar NPCs vivos andando pelo mundo — cuidando dos bichos, escoltando jogadores, atirando nos monstros — eu fiz o que **literalmente o planeta inteiro faz** há mais de dez anos: `[pausa]` eu subi bots em Node.js usando o **Mineflayer**.
>
> Em menos de uma hora, o caos estava instalado. `[tela]`
>
> Primeiro: a Karlach ficava agachando sozinha no meio do corredor sem ninguém mandar. Segundo: quando ela entrava na jaula para alimentar os bichos, o timer vencia antes de ela cruzar a porta e **ela se trancava lá dentro**. Terceiro: toda vez que alguém puxava um fuzil de mod na mão, o terminal do Node cuspia erros bizarros de pacote binário corrompido. `[pausa]`
>
> E quando eu rodei um `htop` na máquina... **três bots parados** estavam comendo **mais de seiscentos megabytes de RAM**.
>
> Eu abri o `node_modules` para entender o porquê. E o que eu encontrei ali dentro explica não só por que a minha NPC ficou presa — mas por que **toda a indústria de Agentes de IA em mundos 3D está construída sobre um gargalo gigantesco.**

---

### ATO II — O QUE EXISTE HOJE (E POR QUE TODO MUNDO USA) `[01:25 — 03:15]`

> Antes de bater no Mineflayer, a gente precisa ser justo e entender o tamanho dele. `[tela]`
>
> Hoje, o Mineflayer tem quase **oito mil estrelas no GitHub**, mais de **cinco mil repositórios dependentes** e cerca de **quarenta mil downloads por semana** no npm. Ele é o topo da pilha **PrismarineJS** — uma coleção de mais de quinze pacotes que implementam o protocolo de rede do Minecraft, física, chunks, inventário e busca de caminhos em JavaScript.
>
> E quem usa isso? Não é só moleque fazendo bot de farm. `[pausa]`
>
> Quando a **NVIDIA** e a Caltech publicaram o **Voyager** — um dos papers mais famosos da história de Agentes Autônomos com LLMs — adivinha o que estava rodando por baixo? **Mineflayer.** Projetos como **Mindcraft**, **ODYSSEY**, **PillagerBench**... quase toda pesquisa moderna de *Embodied AI* escreve a inteligência em Python, abre uma ponte gambiarra chamada `JSPyBridge` para um processo Node.js separado, e entrega o controle para o Mineflayer.
>
> Mas... e as alternativas? O que mais existe no mercado hoje? `[tela]`
>
> - Na ponta da performance extrema existe o **Azalea**, em **Rust**, usando o ECS da engine Bevy. Ele é rápido e gasta pouca memória, mas é baixo nível, focado em Vanilla e não entrega uma camada pronta para agentes cognitivos ou servidores pesados de mods.
> - No mundo Java existe a **MCProtocolLib**, mantida pelo pessoal do Geyser, mas ela é **só o cano de rede** — você não ganha física, nem mundo, nem navegação.
> - Existe o **Baritone**, que tem o melhor pathfinder A\* já feito para Minecraft, mas ele vive preso dentro de um cliente gráfico pesado de jogo, não como um motor limpo multi-agente.
>
> Resultado? Por inércia e falta de um substituto completo, todo mundo continua caindo no Mineflayer. `[pausa]` Só que a arquitetura do Mineflayer nasceu em **2011**. E o peso da idade cobrou a conta.

---

### ATO III — A AUTÓPSIA: OS 5 PROBLEMAS ESTRUTURAIS `[03:15 — 06:00]`

> Quando a gente analisa o Mineflayer sob a lente de **engenharia de software rigorosa**, cinco falhas estruturais saltam na cara. `[tela]`
>
> **Problema número um: o God-Object Mutável.**
> Quando você chama `createBot()`, o Mineflayer cria um único `EventEmitter` global e carrega **quarenta e um plugins internos**. Como esses plugins funcionam? *Monkey-patching* direto! Cada plugin pendura métodos e estados mutáveis no mesmo objeto `bot`. Não existe isolamento de escopo, não existe *ownership* das pernas ou das mãos do bot. Se o plugin de caminho manda correr e um evento de chat manda agachar no mesmo milissegundo, os estados colidem e o seu bot vira uma estátua agachada.
>
> **Problema número dois: Duplicação Brutal de Mundo e RAM.** `[tela]`
> Cada bot no Mineflayer sobe seu próprio heap V8, carrega megabytes de JSONs estáticos do `minecraft-data` e mantém **uma cópia inteira dos chunks ao redor**. Se você colocar trinta guardas numa vila, os mesmos blocos da vila são descomprimidos e copiados **trinta vezes na sua memória RAM**. São seis gigabytes jogados no lixo para trinta NPCs!
>
> **Problema número três: Servidores com Mods Quebram na Rede.**
> O parser binário deles, o `ProtoDef`, lê pacotes usando esquemas estáticos do jogo Vanilla. No Minecraft moderno, mods como *Just Enough Guns* ou *Naturalist* adicionam componentes de dados customizados nos itens e novas entidades. O parser lê metade dos bytes, se perde no tamanho do pacote, descarta o resto e passa a enxergar capivaras e elefantes como... `'unknown'`.
>
> **Problema número quatro: Bugs Reais Escondidos no Pathfinder.** `[tela]`
> Olha o que nós achamos abrindo o código-fonte do `mineflayer-pathfinder`:
> Primeiro, ele roda o algoritmo **A\* de forma síncrona na única thread do Node**, com um orçamento padrão de **quarenta milissegundos** a cada tick de cinquenta! Um único bot pensando numa rota difícil trava **oitenta por cento** do Event Loop!
> Segundo: a flag `canOpenDoors` vem desligada por padrão com um comentário dizendo *"causa problemas"*, a lista de blocos abertos ignora portas de madeira, e em `movements.js` ele só checa se há portão na altura do **pé** — nunca na altura da **cabeça**! No nosso santuário, os portões tinham dois blocos de altura para os bichos não pularem. Para o Mineflayer, aquilo era uma parede de pedra! Ah, e no arquivo `goals.js`, a classe `GoalBreakBlock` literalmente esquece de passar o parâmetro `node` na função `isEnd()` e dá `TypeError` na hora!
>
> **E o problema número cinco — o pior de todos — o Caos com LLMs.** `[tela]`
> Como o Mineflayer não tem um protocolo declarativo seguro para IA, frameworks famosos pedem para o GPT ou Claude **gerar código JavaScript cru em texto** e rodam `eval()` direto no processo! Além da brecha absurda de segurança, pensa na física temporal: o jogo roda a **vinte ticks por segundo** — um tick a cada cinquenta milissegundos. Uma LLM leva **dois segundos** para responder. Enquanto a IA está escolhendo palavras na nuvem, o bot fica **quarenta ticks em coma**, apanhando de zumbi sem reação!

---

### ATO IV — A VIRADA NEXT-GEN: COMO FUNCIONA O `craft-agents` `[06:00 — 10:30]`

> Foi olhando para essa autópsia que nasceu a arquitetura do **`craft-agents`**. `[pausa]`
>
> A pergunta que guiou o projeto não foi *"como fazer um wrapper melhorzinho em cima do JS?"*. A pergunta foi: **como a gente projeta um motor de agentes de próxima geração, do zero, combinando Clojure 1.12 e Java 21?**
>
> Tudo se apoia em **cinco ideias-chave**. Vamos abrir uma por uma. `[tela]`

#### Ideia-Chave 1: Arquitetura *Dual-Backend* (Zero-Copy no Servidor + Swarm na Rede) `[06:30 — 07:35]`
> A primeira grande sacada é separar completamente o **Cérebro do Agente** do **Meio de Transporte** através de uma fronteira limpa (`VoxelAccessor` e `IWorldBackend`).
>
> Quando o servidor é seu — como no nosso servidor Fabric com dezenas de mods — por que cargas d'água o bot deveria fingir que é um cliente externo abrindo socket TCP e sofrendo pra ler bytes?
> No **Modo A (Server-Native)**, o `craft-agents` injeta os agentes **direto dentro da JVM do servidor** usando um `ServerPlayerEntity` conectado a um canal Netty silencioso em memória (`EmbeddedChannel`).
> O que isso significa na prática? `[pausa]`
> Para todos os mods do jogo, ela é uma jogadora 100% real: usa skins customizadas, equipa fuzil, atrai capivaras, tem inventário completo e sofre a física oficial da própria Mojang — **zero risco de desync**. Só que os pacotes de saída são descartados na memória sem gastar CPU nem rede. O custo cai de **duzentos megabytes** no Node para **menos de quinhentos kilobytes por agente**!
>
> *"Ah, mas e se eu quiser conectar num servidor externo onde eu não sou dono da máquina?"*
> É aí que entra a metade dois do **Dual-Backend**: o mesmo cérebro troca apenas o adaptador de baixo para um cliente TCP sobre **Virtual Threads do Java 21**, onde **quinhentos bots compartilham um único cache imutável de chunks** na memória em vez de duplicar o mapa quinhentas vezes!

#### Ideia-Chave 2: `PathSolver3D` Zero-Allocation + Portais Atômicos `[07:35 — 08:30]`
> Segunda ideia-chave: navegação espacial tratada como engenharia numérica séria. `[tela]`
>
> Nós escrevemos o calculador de rotas 3D em **Java 21 puro sem alocação de objetos**. As coordenadas `(x, y, z)` são empacotadas bit a bit dentro de um único primitivo `long` de 64 bits — zero pressão no Garbage Collector — e o cálculo roda em *Worker Threads* fora da thread principal do jogo, lendo snapshots seguros das caixas de colisão reais (`VoxelShape`), inclusive móveis e blocos de mods!
>
> E sabe o problema da jaula? No `craft-agents`, portas e portões — simples ou **duplos empilhados** — são reconhecidos como **Portais Semânticos Transacionais**. A travessia é uma máquina de estados com **pós-condição garantida**: o agente abre todos os blocos do grupo, monitora matematicamente o produto escalar até ter certeza de que o corpo inteiro cruzou o plano da porta, e executa um bloco `finally` que **tranca tudo atrás dele** — mesmo se um monstro atacar no meio do caminho!

#### Ideia-Chave 3: Behavior Trees em Dados Puros + *Leases* Físicos `[08:30 — 09:20]`
> Terceira ideia-chave: matar de vez o *callback hell* usando **Dados Imutáveis em Clojure (EDN)** validados por **Malli**, somados a **Leases de Atuadores**. `[tela]`
>
> No `craft-agents`, o corpo do bot é dividido em três canais independentes com posse exclusiva: **`:legs`** (pernas), **`:hands`** (mãos) e **`:gaze`** (olhar).
> Para agachar, andar ou atirar, um nó da Behavior Tree precisa adquirir o *lease* daquele canal com uma prioridade numérica. Isso permite que a Karlach **ande pelo corredor usando `:legs`, olhe nos seus olhos quando você passa ao lado usando `:gaze`, e acene com `:hands`** — mas torna **matematicamente impossível** dois sistemas brigarem pelas pernas ao mesmo tempo! Se o combate de prioridade cem interrompe uma rotina de prioridade quarenta, o sistema limpa a postura antes de entregar o controle.

#### Ideia-Chave 4: Cérebro em 3 Camadas e o Verdadeiro Papel da LLM `[09:20 — 10:15]`
> Quarta ideia-chave — e talvez a mais importante para quem trabalha com IA hoje: **a LLM é o Comandante, nunca o Motorista no Volante.** `[tela]`
>
> Nós dividimos a mente de cada agente em três velocidades, igual na robótica moderna:
> - **Camada 1 (50ms / 20 Hz):** Reflexos motores em JVM pura. Desviar de fogo, revidar dano imediato, manter a física. Zero IA, zero rede.
> - **Camada 2 (50ms a 200ms):** A Behavior Tree lendo um **Blackboard** imutável. Noventa e cinco por cento da vida do NPC — patrulhar, abrir cercado, alimentar bicho, escoltar — roda aqui gastando **zero tokens**!
> - **Camada 3 (Assíncrona / Event-Driven via MCP):** A LLM só é acordada quando você conversa com o bot, ou quando a Behavior Tree bate num erro que não sabe resolver sozinha — tipo *"acabou a munição, e agora?"*.
>
> Quando ela acorda, ela não recebe quatro mil blocos de texto inútil: ela recebe um **Semantic Scene Digest** de quatrocentos tokens dizendo exatamente quem está visível, quais cercados estão perto e o que está equipado. E em vez de `eval()` inseguro, ela responde via **Model Context Protocol (MCP)** escrevendo uma nova diretiva no Blackboard — ou até **ensinando uma nova Skill permanente** ao bot combinando blocos de Behavior Tree que passam por um validador estrito no **Malli**. Aprendizado contínuo estilo *Voyager*, com **risco zero** e execução instantânea nas próximas vezes!

#### Ideia-Chave 5: Observabilidade Viva (`Agent Studio` + Live Coding) `[10:15 — 10:50]`
> E a quinta ideia-chave: chega de debugar bot no escuro dando `console.log`. `[tela]`
>
> O motor já sobe o **Agent Studio** no navegador via WebSocket: você vê a árvore de comportamento acendendo em verde em tempo real, vê quem é dono das pernas, das mãos e do olhar, acompanha o radar 2D da rota A\*, edita o arquivo `.edn` do agente com **hot-reload instantâneo** sem desconectar o bot do mundo, e ainda pode ligar um botão que desenha a rota com partículas **dentro do jogo** visíveis só para você.

---

### ATO V — FECHAMENTO: O QUE ISSO DESTRAVA `[10:50 — 11:45]`

> Quando você junta essas cinco peças, o jogo vira completamente. `[pausa]`
>
> Para quem tem servidor modded ou cria mundos de RPG, significa ter dezenas de NPCs realmente vivos na mesma máquina gastando menos memória que uma única aba do navegador.
> Para quem pesquisa **IA Incorporada** em Python, Clojure ou qualquer linguagem, significa plugar qualquer modelo direto num **Servidor MCP nativo**, sem pontes quebradiças para JavaScript e sem congelar o agente a cada pensamento.
> E para grandes redes, significa simular centenas de jogadores reais testando o servidor numa fração do hardware atual.
>
> A Karlach finalmente vai poder cuidar das capivaras em paz — e trancar a porta na saída. `[pausa]`
>
> Toda a arquitetura, os diagramas e o backlog aberto de cinquenta e duas tasks do **`craft-agents`** já estão documentados sob licença MIT. Nos próximos vídeos dessa série, a gente vai colocar a mão no código da Fase 1 ao vivo dentro da JVM do Minecraft.
>
> Se você curte engenharia de software sem atalhos, deixa o like, se inscreve, e me conta nos comentários: **que tipo de agente autônomo você colocaria pra viver num mundo desses?** Até o próximo!

---

## 3. Shot List & Direção Visual Sincronizada (YouTube 16:9)

| Tempo | Cena / Visual na Tela | Texto / Callout Gráfico | Notas de Ritmo e Edição |
| :--- | :--- | :--- | :--- |
| **00:00–00:35** | Gameplay real no santuário: Karlach agachando estranho e ficando trancada atrás do portão duplo com as capivaras. | **PRESA NA PRÓPRIA JAULA** | Começar direto na cena in-game (zero vinheta longa). Corte rápido para câmera nos `00:15`. |
| **00:35–01:25** | Split-screen do terminal Linux mostrando `Chunk size is 42 but only 22 was read` + `htop` destacando os processos `node` somando `>600 MB RAM`. | **3 BOTS = +600 MB DE RAM** | Zoom dramático na barra de memória e no erro do `entity_equipment`. |
| **01:25–02:20** | Captura da página npm do `mineflayer` (~40k/sem), paper do *Voyager* (NVIDIA) e diagrama da ponte Python → `JSPyBridge` → Node.js. | **2011 → HOJE: O PADRÃO DE FACTO** | Mostrar o diagrama de 6 camadas do documento `01-analise-mineflayer-e-mercado.md`. |
| **02:20–03:15** | Quadro comparativo animado (Mineflayer vs Azalea vs MCProtocolLib vs Baritone) preenchendo os `❌` e `⚠️`. | **O VÁCUO DO MERCADO** | Usar os dados da Matriz Competitiva (Seção 6 do Doc 01). |
| **03:15–04:30** | Animação limpa do `bot` EventEmitter recebendo setas caóticas de 41 plugins mutando `controlState` + visual de 30 cópias de chunks na RAM. | **1. GOD-OBJECT MUTÁVEL** / **2. CHUNKS DUPLICADOS** | Contraste visual forte em vermelho para mutação concorrente. |
| **04:30–05:30** | Close no código real aberto no editor: `movements.js` (linhas 94–101 e 387–395) e `goals.js` (linha 216 sem `node` em `GoalBreakBlock`). | **PROVA NO CÓDIGO-FONTE** | Destacar com marca-texto amarelo a linha exata do bug no código oficial! Momento de altíssima autoridade técnica. |
| **05:30–06:00** | Linha do tempo comparando `1 Tick = 50ms` vs `LLM = 2000ms (40 Ticks Cego)` enquanto um zumbi ataca um bot estático + alerta `eval()`. | **40 TICKS EM COMA** | Efeito sonoro de relógio desacelerando enquanto o bot apanha parado. |
| **06:00–07:35** | Transição limpa (tema escuro/ciano): entra diagrama **Dual-Backend** (`craft-agents`). Animação do `ServerPlayerEntity` + `EmbeddedChannel` descartando pacotes. | **IDEIA #1: DUAL-BACKEND (< 500 KB/BOT)** | Mostrar comparativo de barra: `205 MB` encolhendo para `0.5 MB`. |
| **07:35–08:30** | Visualização de bits do `long` 64-bit `(x, z, y)` + diagrama `stateDiagram-v2` do `traverse-portal` (`Abrir → Cruzar Plano → Fechar`). | **IDEIA #2: PATHSOLVER 3D + PORTAIS ATÔMICOS** | Destacar a seta `Preempção/Erro → FecharPortal (finally)`. |
| **08:30–09:20** | Snippet EDN da Behavior Tree de `karlach.edn` + 3 cadeados luminosos independentes: `:legs`, `:hands`, `:gaze`. | **IDEIA #3: BEHAVIOR TREES + LEASES** | Ilustrar os 3 canais operando juntos sem conflito. |
| **09:20–10:15** | Diagrama Cognitivo de 3 Camadas (`System 0 / 1 / 2`) + `sequenceDiagram` paralelo + fluxo do Gatekeeper **Malli** aprovando uma nova Skill sem `eval()`. | **IDEIA #4: LLM COMO COMANDANTE (MCP)** | Enfatizar `"95% da vida = 0 tokens"` e `"Voyager seguro sem eval()"`. |
| **10:15–10:50** | Mockup/tela do **Agent Studio** (grafo verde ao vivo + radar 2D) + visão in-game com trilha de partículas desenhando o caminho no chão. | **IDEIA #5: OBSERVABILIDADE VIVA** | Transição rápida do navegador para dentro do jogo. |
| **10:50–11:45** | Apresentador em plano médio + B-roll final no servidor ao entardecer com os NPCs patrulhando suavemente e o repositório `craft-agents` aberto. | **CRAFT-AGENTS · MIT OPEN SOURCE** | Encerramento firme convidando para acompanhar a série de construção. |

---

## 4. Checklist de Capturas Reais para Gravar Hoje no Seu Ambiente

Você já tem quase todas as provas prontas na máquina para gravar B-rolls autênticos fortíssimos:
1. **O Crime da Memória:** Gravar `ps -o pid,rss,comm,args -p $(pgrep -f npc_)` mostrando os ~205 MB RSS de cada script JS.
2. **A Prova no Código Oficial:** Gravar o editor abrindo `/home/pace/dev/craft-repl/bot/node_modules/mineflayer-pathfinder/lib/movements.js` na linha 101 (`canOpenDoors = false`) e linha 387, e `lib/goals.js` na linha 216 (`return this.goal.isEnd()`).
3. **Os Diagramas Arquiteturais:** Exibir os diagramas Mermaid renderizados de `02-arquitetura-e-plano-craft-agents.md` e `03-comportamento-e-cognicao-llm.md`.
4. **O Santuário e os Portões Duplos:** Gravar 15 segundos dentro do Minecraft mostrando os portões duplos em `[-171, 65, 171]`, as capivaras do *Naturalist* e o fuzil do *JEG*.

---

## 5. Versão Curta Derivada (60 Segundos — Vertical 9:16 p/ Reels, Shorts & TikTok)

> **Duração alvo:** 58 segundos | **Ritmo:** Cortes secos, energético, direto ao ponto.

**[00:00 — 00:06] GANCHO**
> Eu coloquei **três bots** em JavaScript pra cuidar do meu servidor de Minecraft... `[pausa]` e eles comeram **seiscentos megas de RAM** pra ficar **trancados dentro de uma jaula.**

**[00:06 — 00:20] O SEGREDO SUJO DO MERCADO**
> Parece piada, mas até os laboratórios de IA da NVIDIA usam essa mesma biblioteca de **2011** chamada **Mineflayer**.
> Quando eu abri o código-fonte dela, descobri o caos: quarenta e um plugins mutando o mesmo objeto global, cópias inteiras do mapa duplicadas pra cada bot, pacotes quebrando com mods, e agentes de IA rodando **`eval()` em JavaScript** enquanto o bot fica em coma esperando a nuvem responder!

**[00:20 — 00:46] A SOLUÇÃO NEXT-GEN (`craft-agents`)**
> Então eu desenhei um substituto do zero em **Clojure e Java 21**: o **`craft-agents`**.
> Em vez de fingir uma conexão pesada de rede no meu próprio servidor, ele injeta jogadores sintéticos **direto na memória da JVM** — caindo de **duzentos megas** para **quinhentos kilobytes por bot**, com suporte nativo a qualquer mod!
> O pathfinder A\* roda fora da thread do jogo em 64 bits e tranca portões duplos com garantia transacional. E a **LLM**? Ela vira o **comandante via MCP**, nunca o motorista no volante: noventa e cinco por cento da rotina roda a **zero tokens** usando Behavior Trees imutáveis.

**[00:46 — 00:58] CTA**
> Código mais limpo, quatrocentas vezes menos memória e pronto para IA de verdade. `[pausa]` Eu soltei o vídeo completo dissecando toda essa arquitetura — **link na bio / no card, e segue pra ver a gente codando isso ao vivo!**
