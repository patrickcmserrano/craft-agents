# Roteiro de Vídeo — Por Que Estamos Substituindo o Mineflayer: A Engenharia do `craft-agents`

> **Projeto:** `craft-agents` — Next-Gen Embodied AI & Autonomous Agent Engine for Minecraft
> **Formato Principal:** Vídeo Horizontal 16:9 (YouTube Deep-Dive de Engenharia — **13 a 15 minutos**)
> **Bônus Incluso (Seção 5):** Corte Vertical 9:16 (**60 segundos** — Reels / Shorts / TikTok)
> **Apêndice (Seção 6):** Backup Consolidado & Decifrado das Anotações de Caderno + Pesquisa Histórica
> **Tom e Estilo:** Engenharia de software real, narrativa investigativa (partindo de um bug real no próprio servidor até dissecar a história da IA em jogos, a aposta em JavaScript de 2011 e o código-fonte do mercado), didático, rítmico e sem hype vazio.
> **Função Narrativa:** Conectar a revolução da *Embodied AI* (*Voyager* / NVIDIA) à sua fundação oculta (*Mineflayer* / *PrismarineJS*), explicar historicamente por que tentaram empurrar tudo para JavaScript em 2011, provar no código por que essa escolha virou uma armadilha hoje, e revelar as **5 Ideias-Chave** da arquitetura do `craft-agents` na JVM moderna.

---

## Opções de Título e Thumbnail (YouTube 16:9)

1. **Opção A (Forte em Engenharia + IA):** *"Do Voyager ao Mineflayer: Por Que Construí um Motor de Agentes de IA do Zero na JVM"*
2. **Opção B (Provocativa / Performance):** *"200 MB por Bot em JavaScript?! A Armadilha de 2011 Por Trás dos Agentes de IA no Minecraft"*
3. **Opção C (Investigativa / Código):** *"Abri o Código do Mineflayer (e do Voyager) — E Decidi Reescrever Tudo em Clojure e Java 21"*

- **Ideia Visual de Thumbnail:** Divisão ao meio. Esquerda em tom vermelho/caótico: logos JS + Voyager + `"210 MB RAM / bot"` + Karlach presa atrás do portão duplo com erro `Chunk size is 42...`. Direita em tom ciano/limpo: Grafo de Behavior Tree iluminado + `"< 500 KB / Zero-Copy JVM"`. Texto curto: **"A ARMADILHA DE 2011"**.

---

## 1. Tese Central do Vídeo

- **Em uma frase:** Os projetos de IA Incorporada mais famosos do planeta (como o *Voyager*, da NVIDIA) demonstraram o poder revolucionário das LLMs em jogos, mas apoiaram essa mente do futuro num cliente TCP externo em JavaScript criado em 2011 — uma escolha compreensível na época do Java 6 burocrático, mas que hoje gera custos proibitivos de API, paralisia temporal por `eval()` e desperdício de memória. O `craft-agents` resolve isso não por uma "guerra" ao Mineflayer, mas contornando totalmente essa camada externa direto na JVM, viabilizando múltiplos agentes autônomos estilo Voyager num servidor multiplayer modded com alta performance, reflexos a 20Hz e custo de tokens reduzido em 95%.
- **O que o espectador leva embora:**
  1. A diferença entre **Reinforcement Learning** (milhões de horas de simulação) e **Embodied AI por LLM** (*Automatic Curriculum*, *Iterative Prompting* e *Skill Library*).
  2. O contexto histórico e jurídico (*Clean-Room*, *Lei de Atwood*, Java 6 vs Node.js) que levou o ecossistema de bots para o JavaScript — e como o renascimento do **Java 21–25** (*Virtual Threads*, *Panama*) e do **Fabric** inverteu completamente o jogo.
  3. Domínio prático de protocolos de rede, pathfinding 3D zero-allocation, Behavior Trees em dados puros, arbitragem por *leases* físicos e separação temporal entre reflexos (`50ms`) e cognição via MCP (`System 0 / 1 / 2`).

---

## 2. Roteiro Falado Completo (YouTube 16:9 — ~14 min)

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
> Só que, para colocar NPCs vivos andando pelo mundo — cuidando dos bichos, escoltando jogadores, atirando nos monstros — eu fiz o que **literalmente o planeta inteiro faz** há mais de uma década: `[pausa]` eu subi bots em Node.js usando o **Mineflayer**.
>
> Em menos de uma hora, o caos estava instalado. `[tela]`
>
> Primeiro: a Karlach ficava agachando sozinha no meio do corredor sem ninguém mandar. Segundo: quando ela entrava na jaula para alimentar as capivaras, o timer vencia antes de ela cruzar o portão duplo e **ela se trancava lá dentro**. Terceiro: toda vez que alguém puxava um fuzil de mod na mão, o terminal do Node cuspia erros bizarros de pacote binário corrompido. `[pausa]`
>
> E quando eu rodei um `htop` na máquina... **três bots parados** estavam comendo **mais de seiscentos megabytes de RAM**.
>
> Eu abri o `node_modules` achando que o erro era meu. Mas o que eu encontrei ali dentro explica não só por que a minha NPC ficou presa — explica por que **a maior revolução de Agentes Autônomos de IA nos games bateu num muro invisível.**

---

### ATO II — DO VOYAGER À APOSTA EM JAVASCRIPT DE 2011 `[01:25 — 05:20]`

#### Bloco II.A — O Marco do Voyager (2023, NVIDIA): De RL para Embodied AI `[01:25 — 02:50]`

> Pra entender o tamanho desse problema, a gente precisa voltar para **maio de 2023**. `[tela]`
>
> Até ali, ensinar uma Inteligência Artificial a jogar videogame significava uma coisa só: **Reinforcement Learning** — Aprendizado por Reforço. Para um bot aprender uma tarefa complexa, você precisava rodar **milhões de horas de simulação** ajustando pesos neurais. Era engessado, específico pra uma única meta e **absurdamente caro**.
>
> Foi aí que o time do **Jim Fan**, na **NVIDIA**, junto com pesquisadores da Caltech e de Stanford, publicou o **Voyager** — o **primeiro agente de IA autônomo baseado em LLM capaz de jogar Minecraft do zero**, sem nenhuma intervenção humana e sem treinamento prévio. `[tela]`
>
> O Voyager inaugurou na prática a era de **Embodied AI por LLM** apoiado em **três mecanismos geniais**:
> 1. Primeiro, um **Automatic Curriculum** — um currículo automático onde a própria IA olha pro inventário e decide o próximo desafio: *"tenho madeira, agora preciso de uma picareta de pedra"*.
> 2. Segundo, o **Iterative Prompting** — um loop iterativo de tentativa e erro: o agente executa uma ação, lê os erros e o feedback do mundo real no terminal, e corrige o próprio plano sozinho.
> 3. E terceiro, a grande joia da coroa: uma **Skill Library** — uma biblioteca evolutiva de habilidades. Toda vez que o Voyager aprendia a fazer algo novo, ele guardava aquela rotina num banco vetorial para reutilizar e combinar no futuro sem nunca esquecer.
>
> Os números assustaram a indústria: `[tela]` o Voyager encontrou diamantes e fabricou ferramentas de diamante sozinho, conquistou **três vírgula três vezes mais itens únicos** e explorou **duas vírgula três vezes mais distância no mapa** do que qualquer outro agente até então! Canais gigantes no YouTube chamaram aquilo de *"o passo final rumo à AGI"*. `[pausa]`
>
> Só que... tenta baixar e rodar o Voyager hoje num servidor real. `[tela]`
> Se você abrir os comentários desses vídeos, o choque de realidade é brutal: *"Ué, parece que o projeto foi abandonado há dois anos?"*, *"Isso custa caro pra cacete na API da OpenAI"*, *"Quebrou tudo tentando logar"*.
> E eles têm razão! O repositório oficial do Voyager **congelou em julho de 2023** — dois meses depois do paper — tentando apagar incêndio de pacotes quebrados. O Jim Fan seguiu em frente para robôs humanóides reais na NVIDIA, startups como a Altera fecharam simulações como o *Project Sid* em nuvens proprietárias... e quem ficou na mão foi a comunidade.
>
> Por que um projeto genial da NVIDIA envelheceu tão rápido? `[pausa]`
> Porque debaixo do cérebro futurista em Python do Voyager, quem controlava o corpo do boneco era uma biblioteca de **2011** chamada **Mineflayer**.

#### Bloco II.B — Mineflayer, PrismarineJS e "Por Que Empurraram Tudo pra JavaScript?" `[02:50 — 04:25]`

> Vamos dar crédito a quem merece: o **Mineflayer** é uma das bibliotecas open-source mais famosas da história dos games, com quase **oito mil estrelas no GitHub** e **quarenta mil downloads por semana**. `[tela]`
>
> Ele foi criado lá em **2011** pelo **Andrew Kelley** — sim, o mesmo engenheiro brilhante que anos depois criou a linguagem **Zig**! Na época, a ideia era provar o poder da **programação assíncrona orientada a eventos** aplicada a protocolos de rede de jogos.
>
> E repara como ele funciona: **o Mineflayer não roda dentro do Minecraft**. Ele é um processo externo em **Node.js** que abre um socket **TCP** com o servidor e **finge ser um cliente oficial** da Mojang.
> Em volta dele nasceu o coletivo **PrismarineJS** — uma família de mais de trinta pacotes que reescreveram chunks, física, inventário e pacotes binários do zero. A ambição deles era tão maluca quanto admirável: **fazer o Minecraft inteiro rodar em JavaScript dentro de uma aba do navegador, sem instalar nada!**
>
> Mas aí vem a pergunta que não quer calar: `[pausa]`
> **Se o Minecraft já é escrito em Java... por que cargas d'água os caras empurraram tudo pra JavaScript em vez de usar o próprio Java ou a JVM?** `[tela]`
>
> A resposta está na fotografia histórica de **2011**. Naquele ano, a programação vivia dois extremos:
> - De um lado, o **Java vivia seu pior momento histórico**, no Java 6 e 7: lento pra subir, burocrático, afogado em XML, sem `var` e **sem lambdas** — que só chegaram em 2014! Na comunidade hacker, falar em Java cheirava a sistema bancário legado dos anos noventa.
> - Do outro lado, o **Node.js** — criado em 2009 pelo Ryan Dahl — era **a coisa mais quente do planeta**. Todo mundo acreditava que o `EventEmitter` não-bloqueante ia engolir o mundo e repetia a famosa **Lei de Atwood**: *"Qualquer aplicação que possa ser escrita em JavaScript, eventualmente será escrita em JavaScript."*
>
> Some a isso dois fatores decisivos:
> Primeiro, a **barreira legal da engenharia reversa "Clean-Room"** — Sala Limpa. Descompilar o `.jar` fechado da Mojang em Java dava medo real de tomar processo e *DMCA*. Inspecionar pacotes de rede por fora e escrever código limpo em JS deixava o projeto cem por cento blindado juridicamente.
> Segundo, a **barreira de entrada**: qualquer desenvolvedor web dava um `npm install mineflayer` e colocava um bot andando no mapa em cinco linhas de código, sem sofrer configurando Eclipse e classpaths.

#### Bloco II.C — Por Que Essa Escolha Virou uma Armadilha Hoje `[04:25 — 05:20]`

> A aposta no JavaScript funcionou lindamente para 2012... **mas envelheceu como leite para os dias de hoje.** `[tela]`
>
> Olha como o tabuleiro virou de cabeça pra baixo:
> 1. **Primeiro: o Java renasceu.** Do Java 21 ao 25, a JVM ganhou **Virtual Threads** com o *Project Loom*, *Records* imutáveis, APIs de memória nativa com o *Project Panama* e compiladores JIT que **humilham o V8** em cálculo numérico e concorrência multithread.
> 2. **Segundo: o modding mudou.** Hoje a própria Mojang publica mapas oficiais de desofuscação, e ferramentas como o **Fabric** permitem injetar código direto na JVM do jogo de forma limpa, segura e legal.
> 3. **Terceiro: física 3D no V8 é um pesadelo de memória.** O motor do Node foi desenhado para renderizar páginas e cuspir JSON — não para guardar milhões de blocos tridimensionais, calcular caixas de colisão AABB e rodar busca A\* sem inteiros compactos de 64 bits!
>
> *"Ah, mas ninguém tentou criar alternativas?"* `[tela]`
> Tentaram, mas ficou um vácuo no mercado: o **Azalea**, em Rust, é super rápido, mas foca só em Vanilla e não tem camada cognitiva; a **MCProtocolLib** no Java é só o cano de rede, sem mundo nem física; e o **Baritone** tem um A\* incrível, mas vive preso num cliente gráfico pesado.
>
> Resumindo a tragédia: `[pausa]` em 2011 fugiram do Java corporativo pela facilidade do Node. Mas o tempo passou, a JVM virou um foguete, as LLMs chegaram exigindo física em tempo real, **e toda a indústria ficou presa numa arquitetura de quinze anos atrás tentando calcular física de jogo dentro do Garbage Collector do V8.**

---

### ATO III — A AUTÓPSIA NO CÓDIGO: OS 5 PROBLEMAS ESTRUTURAIS `[05:20 — 08:05]`

> Quando a gente abre o capô do Mineflayer sob a lente de **engenharia de sistemas rigorosa**, cinco falhas estruturais explicam exatamente tudo o que deu errado. `[tela]`
>
> **Problema número um: o God-Object Mutável.**
> Quando você chama `createBot()`, o Mineflayer instancia um único `EventEmitter` global e carrega **quarenta e um plugins internos** via *monkey-patching*! Cada plugin pendura métodos e estados mutáveis no mesmo objeto `bot`. Não existe isolamento de escopo, não existe *posse exclusiva* das pernas ou das mãos. Se o pathfinder manda correr e um listener de chat manda agachar no mesmo milissegundo, os estados colidem e o seu NPC vira uma estátua agachada!
>
> **Problema número dois: Duplicação Brutal de Mundo e RAM.** `[tela]`
> Como cada bot é um cliente externo isolado, cada instância sobe heap V8, carrega megabytes de JSONs estáticos do `minecraft-data` e mantém **uma cópia inteira dos chunks ao redor**. Se você colocar trinta guardas numa vila, os mesmos blocos da vila são descomprimidos e copiados **trinta vezes na memória RAM**. São seis gigabytes jogados no lixo para trinta NPCs!
>
> **Problema número três: Servidores com Mods Quebram na Rede.** `[tela]`
> O parser binário deles, o `ProtoDef`, lê pacotes usando esquemas estáticos do jogo Vanilla. No Minecraft 1.21, mods como *Just Enough Guns* ou *Naturalist* adicionam novos componentes NBT nos itens e entidades customizadas. O parser lê metade dos bytes, se perde no tamanho do pacote com `Chunk size is 42 but only 22 was read`, descarta o resto e passa a enxergar capivaras e elefantes como... `'unknown'`.
>
> **Problema número quatro: Bugs Reais Escondidos no Pathfinder.** `[tela]`
> Olha o que nós achamos abrindo o código-fonte oficial do `mineflayer-pathfinder`:
> Primeiro, ele roda o algoritmo **A\* de forma síncrona na única thread do Node**, com orçamento padrão de **quarenta milissegundos** a cada tick de cinquenta! Um único bot calculando rota difícil trava **oitenta por cento** do Event Loop!
> Segundo: na linha 101 de `movements.js`, a flag `canOpenDoors` vem `false` por padrão com um comentário confessando *"Causes issues"*! Na linha 389, ele só checa se há portão na altura do **pé** — nunca na altura do **tronco**! No nosso santuário, os portões eram duplos para os bichos não fugirem: para o Mineflayer, aquilo era uma parede sólida! E de brinde, na linha 216 de `goals.js`, a classe `GoalBreakBlock` esquece de passar o argumento `node` para `isEnd()` e estoura `TypeError` em runtime!
>
> **E o problema número cinco — o "Pecado Original" do Voyager com LLMs.** `[tela]`
> Como o Mineflayer não tem um protocolo declarativo tipado, projetos em Python abrem uma ponte IPC gambiarra chamada `JSPyBridge`, pedem para o GPT ou Claude **gerar código JavaScript cru em texto** e executam **`eval()` direto no processo**!
> Além do risco absurdo de loop infinito ou alucinação corromper o estado global, olha a matemática do tempo: o Minecraft roda a **vinte ticks por segundo** — um tick a cada **cinquenta milissegundos**. Uma LLM na nuvem leva **dois segundos** para responder. Enquanto a IA escolhe palavras, o bot fica **quarenta ticks em coma**, apanhando de zumbi sem reação — enquanto queima dezenas de dólares de API pra dar meia dúzia de passos!

---

### ATO IV — O PRÓXIMO PASSO DO VOYAGER: A ARQUITETURA DO `craft-agents` `[08:05 — 12:35]`

> Quando a gente analisa a autópsia do Mineflayer e olha para o que o time da NVIDIA fez no **Voyager** em 2023, uma coisa fica muito clara: `[pausa]`
> Os pesquisadores acertaram em cheio na tese de **Embodied AI por LLM**. O trio de **Automatic Curriculum**, **Iterative Prompting** e **Skill Library** foi um divisor de águas absoluto na história da inteligência artificial aplicada a games.
>
> Mas a fundação de engenharia onde eles apoiaram essa mente do futuro era o que havia disponível em 2011: um cliente TCP externo em JavaScript. E isso gerou os gargalos que os desenvolvedores e YouTubers sentiram na pele:
> - O **custo astronômico de API**: a cada bloco minerado ou passo dado, a LLM gerava código JavaScript novo em prompts caríssimos de dezenas de dólares por hora.
> - A **paralisia temporal do bot**: o agente ficava quarenta ticks em coma apanhando de zumbi enquanto a IA na nuvem digitava uma resposta.
> - A **fragilidade do `eval()`**: código cru gerado em texto que quebrava por erros de sintaxe ou loops infinitos.
> - E o **isolamento single-player**: um processo pesado demais para colocar múltiplos NPCs convivendo num mundo multiplayer real.
>
> A nossa proposta com o **`craft-agents`** e o ecossistema do **`craft-repl`** não é "fazer guerra" ou desmerecer o Mineflayer. Pelo contrário: é reconhecer o papel de cada tecnologia e dar o próximo salto que o ecossistema moderno hoje permite: `[pausa]`
> **Em vez de remendar um socket TCP externo, nós contornamos totalmente essa camada direto na JVM do Minecraft.**
>
> O objetivo central do software é viabilizar o **experimento Voyager em escala multiplayer**: dezenas de agentes autônomos com currículo aberto, reflexos vivos e aprendizado evolutivo habitando um servidor real de Minecraft junto com jogadores humanos — com alta performance, física oficial exata e custo de tokens reduzido em noventa e cinco por cento. `[tela]`
>
> Para transformar essa tese em software funcional, nós construímos uma arquitetura apoiada em **cinco inovações concretas**. Vamos abrir o capô de cada uma delas. `[tela]`

#### Inovação 1: Modo Server-Native Zero-Copy — Múltiplos Agentes Voyager no Multiplayer `[08:35 — 09:30]`
> A primeira grande inovação responde à pergunta: por que um bot no seu próprio servidor precisa fingir que é um jogador de fora da internet abrindo conexão TCP? `[tela]`
>
> No **Modo Server-Native**, o `craft-agents` injeta os agentes **direto dentro da JVM do servidor Fabric** usando a classe oficial `ServerPlayerEntity` acoplada a um canal silencioso em memória (`SilentEmbeddedChannel`).
>
> Na prática? `[pausa]` Para todos os mods do servidor — armas do *JEG*, capivaras e elefantes do *Naturalist* —, a Karlach e o Capitão Picard são jogadores cem por cento reais: sofrem a física exata da Mojang com **zero risco de desync**, usam itens customizados e leem os blocos do mapa **direto da memória RAM sem duplicar nenhum chunk**!
> Os pacotes binários de rede que seriam transmitidos são descartados na memória sem tráfego de socket. O consumo despenca de **duzentos e dez megabytes** no Node para **menos de quinhentos kilobytes por agente**!
> Isso significa que o servidor pode hospedar **trinta ou cinquenta agentes estilo Voyager** vivendo no mesmo mundo multiplayer sem sobrecarregar a máquina.
>
> *(E se você precisar se conectar num servidor externo fechado? O motor tem o **Modo Swarm**: o mesmo cérebro troca o adaptador por clientes TCP sobre **Virtual Threads do Java 21**, onde quinhentos bots compartilham um único cache imutável de blocos em vez de clonar o mundo quinhentas vezes).*

#### Inovação 2: Arquitetura Tri-Temporal (`System 0 / 1 / 2`) — Fim da Paralisia e Redução de Custo de 95% `[09:30 — 10:25]`
> A segunda inovação resolve de vez o maior pesadelo do Voyager original: **o custo financeiro e o bot em coma**. `[tela]`
>
> No Voyager, a LLM tentava ser tudo ao mesmo tempo: o cérebro estratégico e o motor das pernas. Cada micro-passo custava tokens e gerava latência de nuvem.
> No `craft-agents`, nós separamos a cognição em **três velocidades temporais desacopladas**:
>
> 1. **System 0 — Reflexos a 20 Hz (JVM local / 50ms):** Roda na mesma taxa de atualização do servidor. Se um creeper chiar pelas costas, uma flecha voar ou o agente encostar em lava, os reflexos autonômicos de esquiva, bloqueio e autodefesa respondem no **mesmo tick** do jogo. **Custo de tokens: zero. Latência: zero.**
> 2. **System 1 — Behavior Trees Declarativas em Clojure (50ms a 200ms):** Patrulhas diárias, tarefas do santuário, alimentação de animais, portais e rotinas de trabalho rodam em dados puros lendo um **Blackboard imutável**. **Noventa e cinco por cento do tempo de vida do NPC roda aqui sem gastar um único centavo de API!**
> 3. **System 2 — Cognição Estratégica via LLM (Assíncrona sob Gatilhos):** A LLM nunca fica no volante do tick. Ela só é acordada sob **gatilhos semânticos** — quando um jogador conversa no chat, quando surge um objetivo desconhecido, ou quando o currículo automático precisa deliberar sobre a meta da próxima hora. O agente continua vivo, respirando e reagindo a ameaças enquanto o cérebro na nuvem formula o plano. O custo mensal de API despenca de centenas de dólares para trocados de pão!

#### Inovação 3: A *Skill Library* Declarativa em EDN com Gatekeeper Malli — Sem `eval()` `[10:25 — 11:15]`
> A terceira inovação aprimora a maior joia do paper da NVIDIA: a **Skill Library evolutiva**, mas eliminando o perigo do `eval()`. `[tela]`
>
> No Voyager de 2023, o GPT gerava código JavaScript cru em formato texto e o Mineflayer executava via `eval()`. Qualquer alucinação, sintaxe quebrada ou loop infinito derrubava o agente.
> No `craft-agents`, **a IA nunca gera código executável em texto livre**.
>
> Quando o agente aprende uma habilidade nova através da ferramenta MCP `compose_and_save_skill`, a LLM propõe uma **sub-árvore declarativa de Behavior Tree estruturada em dados puros (EDN)**.
> Antes de entrar na biblioteca do agente, ela é submetida ao **Gatekeeper formal em Clojure**:
> - O **Malli** valida o esquema estático da árvore;
> - O validador confere se todas as ações chamadas pertencem a um catálogo seguro de ações conhecidas;
> - Todos os nós de espera recebem timeouts obrigatórios;
> - E um algoritmo de análise de grafos roda checagem topológica para **impedir recursão cíclica e loops infinitos matematicamente**!
>
> Uma vez aprovada pelo Gatekeeper, a habilidade é persistida em disco e passa a rodar como rotina local nativa no System 1 — **para sempre, a vinte hertz e custo zero de tokens!**

#### Inovação 4: `PathSolver3D` Zero-Allocation e Portais Semânticos Atômicos `[11:15 — 11:55]`
> A quarta inovação ataca a física espacial 3D e a armadilha dos portões duplos onde a Karlach se trancava. `[tela]`
>
> Escrevemos o `PathSolver3D` em **Java 21 puro sem alocação de objetos no loop de busca A\***. As coordenadas `(x, y, z)` são empacotadas bit a bit dentro de um único primitivo `long` de 64 bits — zero lixo para o Garbage Collector —, rodando em *Virtual Threads* fora da thread principal do servidor e lendo os dados reais de `VoxelShape` de blocos do jogo e de mods.
>
> E a passagem por cercados? Portas e portões — simples ou **duplos empilhados** — deixam de ser tratados como blocos isolados com `setTimeout` cego e viram **Portais Semânticos Transacionais**. A travessia é uma máquina de estados: o agente abre os blocos do portal no mesmo tick, monitora pelo produto escalar até o centro da caixa de colisão cruzar a linha do portal, e executa uma cláusula `finally` garantida que **fecha e tranca a passagem com segurança** — mesmo se for interrompido por um ataque no meio do caminho!

#### Inovação 5: Arbitragem por *Leases* Físicos e Observabilidade Viva no `Agent Studio` `[11:55 — 12:35]`
> E a quinta inovação enterra o bug do *God-Object* mutável e o problema de debugar bots no escuro. `[tela]`
>
> Para impedir que um comando de chat conflite com o pathfinder e faça a Karlach andar agachada para sempre, o corpo de cada agente é protegido por **Leases Físicos Concorrentes**:
> O atuador é dividido em três canais independentes com posse exclusiva: **`:legs`** (pernas), **`:hands`** (mãos) e **`:gaze`** (olhar).
> Cada canal só pode ser controlado por uma intenção com prioridade registrada. A Karlach consegue patrulhar usando `:legs`, olhar nos olhos de quem passa usando `:gaze` e acenar usando `:hands` simultaneamente. Se surge um monstro com prioridade cem, ela assume a posse imediata das pernas e mãos para combate, limpando qualquer estado anterior antes de devolver o controle para a patrulha.
>
> E tudo isso ganha vida visual no **`Agent Studio`**: uma interface web moderna conectada via WebSocket onde você assiste as Behavior Trees acendendo em tempo real, monitora os *leases* de atuadores, visualiza o radar de navegação e pode fazer **hot-reload instantâneo** de diálogos e comportamentos em `.edn` sem reiniciar o Minecraft nem desconectar os agentes!

---

### ATO V — FECHAMENTO: O QUE ISSO DESTRAVA `[12:35 — 13:30]`

> Quando você junta essas cinco peças na JVM moderna, o jogo muda de patamar. `[pausa]`
>
> Para criadores de mundos e servidores modded, significa dezenas de NPCs vivos na mesma máquina consumindo menos RAM que uma aba de navegador.
> Para pesquisadores de **Embodied AI** que esbarraram nos limites de custo e fragilidade do *Voyager*, significa plugar qualquer LLM ou agente Python direto num **Servidor MCP nativo**, com aprendizado contínuo seguro e sem deixar o corpo do agente em coma a cada pensamento.
> E para grandes redes, significa simular centenas de jogadores sobre Virtual Threads numa fração do hardware.
>
> A Karlach finalmente vai cuidar das capivaras em paz — e trancar o portão duplo na saída. `[pausa]`
>
> Toda a arquitetura, a matriz de testes TDD e o backlog de cinquenta e duas tasks do **`craft-agents`** já estão abertos sob licença MIT. Nos próximos vídeos, a gente vai construir cada fase ao vivo dentro da JVM do Minecraft.
>
> Se você curte engenharia de software de verdade, deixa o like, se inscreve no canal e comenta aqui embaixo: **que tipo de agente autônomo você colocaria pra viver e aprender num mundo desses?** Até o próximo!

---

## 3. Shot List & Direção Visual Sincronizada (YouTube 16:9)

Todos os **23 assets gráficos e capturas autônomas (1920x1080)** abaixo já estão prontos em [`assets/video-capturas/`](file:///home/pace/dev/craft-agents/assets/video-capturas/).

| Tempo | Cena / Visual na Tela (Assets Prontos + Takes) | Texto / Callout Gráfico | Notas de Ritmo e Edição |
| :--- | :--- | :--- | :--- |
| **00:00–00:35** | **Take In-Game:** Santuário em `[-171, 65, 171]` mostrando Karlach agachando errado e presa atrás do portão duplo com as capivaras. | **PRESA NA PRÓPRIA JAULA** | Entrar direto na cena in-game sem vinheta. Corte rápido para câmera nos `00:15`. |
| **00:35–01:25** | Split-screen / corte seco: erro binário (`11_terminal_protodef_error.png`) + medição de RAM (`10_terminal_memory_htop_600mb.png`). | **3 BOTS PARADOS = +625 MB RAM** | Zoom dramático no `Chunk size is 42...` e na soma de RSS do Node. |
| **01:25–02:20** | Paper do Voyager (`03_web_voyager_paper.png`) + Infográfico completo RL vs LLM, 3 Mecanismos e métricas `3,3x` / `2,3x` (`03a_diagram_voyager_anatomia.png`). | **VOYAGER (2023): RL vs EMBODIED AI** | Destacar os 3 pilares (*Automatic Curriculum*, *Iterative Prompting*, *Skill Library*) e os resultados de diamantes. |
| **02:20–02:50** | Vídeo WorldofAI (`03c_web_voyager_youtube_worldofai.png`), repo congelado em Jul/2023 (`03b_web_voyager_github_repo.png`) e card de comentários reais (`03d_card_voyager_user_comments.png`). | **O CHOQUE DE REALIDADE (JUL/2023)** | Contrastar a promessa de "quase AGI" com as queixas reais de abandono e custo de API. |
| **02:50–03:35** | Página npm (`01_web_mineflayer_npm.png`), GitHub (`02_web_mineflayer_github.png`) e Torre de 6 Camadas PrismarineJS (`12_diagram_prismarine_stack.png`). | **MINEFLAYER (2011) & PRISMARINEJS** | Mostrar que é um processo externo Node.js via TCP + ambição de rodar o Minecraft no browser. |
| **03:35–04:50** | Card Histórico 2011 vs Hoje (`12b_diagram_historia_js_vs_jvm.png`): Java 6 vs Node/Lei de Atwood/Clean-Room → Renascimento Java 21–25 (Loom/Panama) + Fabric. | **POR QUE JAVASCRIPT EM 2011? (E A ARMADILHA HOJE)** | Revelar coluna esquerda nos motivos de 2011 e coluna direita quando falar que "envelheceu como leite". |
| **04:50–05:20** | Repositórios concorrentes (`04_azalea`, `05_mcprotocollib`, `06_baritone`) culminando na Matriz Competitiva (`13_diagram_matriz_competitiva.png`). | **O VÁCUO DO MERCADO** | Passagem rápida pelas 3 alternativas fechando na tabela comparativa completa. |
| **05:20–06:25** | Diagrama do God-Object com 41 plugins colidindo no `EventEmitter` (`14_diagram_god_object_mutavel.png`) + retorno visual da duplicação de RAM. | **1. GOD-OBJECT MUTÁVEL** / **2. CHUNKS DUPLICADOS** | Contraste vermelho forte ilustrando colisão entre pathfinder e eventos de chat. |
| **06:25–07:30** | Provas no código oficial: `07_code_movements_can_open_doors.png` (linha 101), `08_code_movements_double_gate.png` (linha 389) e `09_code_goals_typeerror.png` (linha 216). | **PROVA NO CÓDIGO-FONTE OFICIAL** | Momento de máxima autoridade técnica: dar zoom nas linhas destacadas do `node_modules`. |
| **07:30–08:05** | Linha do tempo `50ms` vs `2000ms` (`16_diagram_latency_timeline.png`) + **Take In-Game** curto de zumbi batendo em bot estático. | **EVAL() + 40 TICKS EM COMA** | Efeito sonoro de relógio desacelerando enquanto a nuvem "pensa". |
| **08:05–09:30** | Mudança de trilha (tema ciano limpo): Diagrama Dual-Backend (`15_diagram_dual_backend.png`) comparando Modo Server-Native (`< 500 KB`) e Modo Swarm (Virtual Threads). | **INOVAÇÃO #1: SERVER-NATIVE MULTIPLAYER (< 500 KB)** | Destacar `ServerPlayerEntity` + `SilentEmbeddedChannel` zero-copy dentro da JVM viabilizando dezenas de agentes Voyager no mesmo servidor. |
| **09:30–10:25** | Pirâmide Cognitiva em 3 Camadas (`18_diagram_cognitive_pyramid.png`) detalhando System 0 (`50ms`/reflexos), System 1 (BT local) e System 2 (MCP assíncrono). | **INOVAÇÃO #2: SYSTEM 0 / 1 / 2 (95% ZERO TOKENS)** | Enfatizar o fim do bot em coma e a redução de custos de dezenas de dólares para centavos. |
| **10:25–11:15** | Diagrama de Composição Segura de Skills e Gatekeeper Malli (`composer.clj`). Visual de árvore EDN sem eval() e detecção estática de ciclos. | **INOVAÇÃO #3: SKILL LIBRARY EDN (SEM EVAL)** | Fechar o arco narrativo do Voyager: aprendizado evolutivo formalmente seguro e livre de quebras em runtime. |
| **11:15–11:55** | Máquina de Estados Transacional de Portais (`17_diagram_atomic_portals.png`) + packing de coordenadas em `long` de 64 bits no `PathSolver3D`. | **INOVAÇÃO #4: PATHSOLVER 3D + PORTAIS ATÔMICOS** | Enfatizar produto escalar e bloco `finally` garantindo trancamento de portões duplos. |
| **11:55–12:35** | Visual dos Leases Físicos (`:legs`, `:hands`, `:gaze`) + Mockup do **Agent Studio** (`19_diagram_agent_studio_mockup.png`) com grafo verde ativo e hot-reload. | **INOVAÇÃO #5: LEASES FÍSICOS & AGENT STUDIO** | Mostrar os 3 canais sem conflito de estado e a transição para observabilidade viva. |
| **12:35–13:30** | Apresentador em plano médio + **Take In-Game** final ao entardecer no santuário com tudo em paz e repo `craft-agents` aberto. | **CRAFT-AGENTS · MIT OPEN SOURCE** | Convite firme para inscrição e acompanhamento da série mão na massa. |

---

## 4. Checklist Prático de Gravação e Pós-Produção (OBS + Whisper)

> **Status dos Assets:** Todos os **23 slides, diagramas, cards de código e capturas web em 1080p** já estão gerados em [`assets/video-capturas/`](file:///home/pace/dev/craft-agents/assets/video-capturas/). O **OBS Studio** já está configurado com cadeia vocal (`RNNoise` + `Compressor` + `Limiter -1.5dB`) e gravação multicanal (`Faixa 1 = Mix`, `Faixa 2 = Voz Isolada`, `Faixa 3 = Jogo/SFX Isolado`).

1. **Takes Manuais Restantes (Minecraft + Câmera):**
   - **Take In-Game 1 (~20s):** Santuário em `[-171, 65, 171]` mostrando os portões duplos de cerca, capivaras (*Naturalist*) e fuzil (*JEG*).
   - **Take In-Game 2 (~15s):** B-roll cinematográfico ao entardecer no santuário para o Ato V.
   - **Talking Head / Voz (~14 min):** Leitura fluida dos Atos I a V (pode gravar por ato/bloco).
2. **Geração Automática de Legendas PT-BR Precisas:**
   ```bash
   mise exec python@3.12.9 -- ./scripts/transcrever.py "/home/pace/Videos/OBS/sua_gravacao.mp4"
   ```
   *(Extrai automaticamente a Faixa 2 limpa e aplica o glossário completo de termos técnicos).*
3. **Expansão Multi-Idioma no YouTube (Opcional):**
   - Usar a `Faixa 2` + `.srt` para dublagem IA em Inglês/Espanhol e remixar com a `Faixa 3` intacta do jogo via `ffmpeg`.

---

## 5. Versão Curta Derivada (60 Segundos — Vertical 9:16 p/ Reels, Shorts & TikTok)

> **Duração alvo:** 58 segundos | **Ritmo:** Cortes secos, energético, legendas dinâmicas palavra por palavra.

**[00:00 — 00:07] GANCHO**
> Eu coloquei **três bots** em JavaScript pra cuidar do meu servidor de Minecraft... `[pausa]` e eles comeram **seiscentos megas de RAM** pra ficar **trancados dentro de uma jaula.**

**[00:07 — 00:24] DO VOYAGER À ARMADILHA DE 2011**
> E não sou só eu: quando a **NVIDIA** criou o famoso **Voyager** — o primeiro agente LLM capaz de pegar diamantes sozinho —, por baixo do capô estava rodando essa mesma biblioteca de **2011**: o **Mineflayer**!
> Em 2011 fugiram do Java burocrático pro Node.js, mas hoje isso virou uma armadilha: quarenta e um plugins mutando estado global, mapa duplicado na RAM, mods quebrando pacotes e IA rodando **`eval()` inseguro** enquanto o bot fica quarenta ticks em coma!

**[00:24 — 00:48] A SOLUÇÃO NEXT-GEN (`craft-agents`)**
> Então eu criei uma arquitetura contornando essa camada externa direto na **JVM em Clojure e Java 21**: o **`craft-agents`**.
> Ele injeta múltiplos agentes estilo Voyager **direto na memória do servidor** — caindo de **duzentos megas** para **quinhentos kilobytes por bot**, compatível com qualquer mod e viável em multiplayer!
> O pathfinder 3D roda sem alocar memória, fecha portões com garantia atômica, e resgata o sonho da *Skill Library* do Voyager via **MCP e Malli**: sem `eval()`, sem bot em coma, e rodando noventa e cinco por cento da vida a **zero tokens**!

**[00:48 — 00:58] CTA**
> Quatrocentas vezes menos memória e engenharia de sistemas real. `[pausa]` O vídeo completo dissecando esse código já está no ar — **clica no link relacionado e segue pra acompanhar a construção ao vivo!**

---

## 6. Apêndice: Backup Consolidado & Decifrado das Anotações de Pesquisa

> Registro limpo unificando as anotações manuscritas do caderno (com os trechos incertos conferidos no paper original `arXiv:2305.16291`) e os levantamentos históricos do projeto.

### 6.1 Voyager (Maio de 2023 — NVIDIA, Caltech, UT Austin & Stanford)
- **Marco Histórico:** Primeiro agente de IA autônomo (*lifelong learning*) baseado em LLM capaz de jogar Minecraft do zero sem intervenção humana.
- **Ruptura com o Paradigma Anterior:** Antes do Voyager, IA em jogos dependia de **Reinforcement Learning (RL)**, exigindo **milhões de horas de simulação** (treinamento caríssimo, recompensas rígidas e baixa generalização).
- **Os 3 Mecanismos Fundamentais:**
  1. **Automatic Curriculum:** Currículo automático gerado por LLM que propõe objetivos progressivos conforme o estado atual do inventário e exploração.
  2. **Iterative Prompting:** *(Termo exato do paper para a anotação do caderno)* — Loop iterativo que gera código de ação, observa o feedback do ambiente e erros de execução, e refina a solução autonomamente.
  3. **Skill Library:** Memória evolutiva indexada em banco vetorial (*ChromaDB*) que armazena programas bem-sucedidos para reutilização e composição hierárquica.
- **Resultados Verificados no Paper Oficial *(decifrando os trechos incertos do caderno)*:**
  - Capaz de encontrar diamantes e fabricar ferramentas de diamante de forma 100% autônoma.
  - Obtém **3,3× mais itens únicos** (*3.3× more unique items*) e percorre **2,3× maiores distâncias / explora 2,3× mais terreno** (*travels 2.3× longer distances*) que os melhores agentes anteriores (AutoGPT, ReAct, Reflexion), além de destravar marcos da árvore tecnológica até **15,3× mais rápido**.
  - Consolidou o campo de **Embodied AI (IA Incorporada) por LLM**.
- **Pós-Publicação:** O repositório oficial (`MineDojo/Voyager`) parou de evoluir em **julho de 2023** esbarrando em quebras de dependências (`prismarine-block`, `chromadb`), alto custo de API OpenAI e fragilidade da ponte Python → `JSPyBridge` → Mineflayer + `eval()`.

### 6.2 Mineflayer (2011) & Ecossistema PrismarineJS
- **Mineflayer (2011, criado por Andrew Kelley — futuro autor do Zig):** Biblioteca open-source em Node.js para criação de bots e clientes *headless* no Minecraft, concebida para demonstrar o poder da programação assíncrona orientada a eventos em protocolos de rede de jogos.
- **Arquitetura Externa:** **Não roda dentro do Minecraft** — opera como um processo Node.js separado que abre conexão TCP com o servidor e emula um cliente oficial.
- **PrismarineJS:** Organização guarda-chuva (+30 pacotes `prismarine-*`) criada com a ambição de reimplementar todo o ecossistema Minecraft em JavaScript — incluindo rodar o cliente 3D inteiro dentro de uma aba do navegador sem instalar nada (`prismarine-viewer`).

### 6.3 Por Que Empurraram Tudo para JavaScript em 2011 — E Por Que Virou Armadilha Hoje
- **Os 4 Motivos Históricos de 2011:**
  1. **Java 6/7 no Pior Momento vs Hype do Node.js:** Java era visto como corporativo, lento, cheio de XML e sem lambdas (só lançadas no Java 8 em 2014), enquanto o Node.js (Ryan Dahl, 2009) e a **Lei de Atwood** dominavam a cultura hacker.
  2. **Barreira Legal (*Clean-Room Reverse Engineering*):** Reescrever o protocolo em JS inspecionando apenas pacotes TCP evitava descompilar o `.jar` proprietário da Mojang, blindando o projeto contra DMCA.
  3. **Barreira de Entrada Web:** `npm install mineflayer` e 5 linhas de script vs configurar JDK, Eclipse e classpaths manualmente.
  4. **Portabilidade para o Browser:** Código JS compartilhável entre Node.js e navegadores WebGL.
- **Os 3 Fatores Que Inverteram o Jogo Hoje:**
  1. **O Java Renasceu (Java 21–25):** *Virtual Threads* (*Project Loom*), *Records* imutáveis e APIs de memória nativa (*Project Panama*) superam largamente o V8 em concorrência e computação numérica.
  2. **O Modding Mudou:** A própria Mojang publica mapas oficiais de desofuscação, e o **Fabric** permite injeção limpa, segura e legal direto na JVM.
  3. **Inadequação do V8 para Física 3D:** O Garbage Collector e o modelo de objetos do V8 foram desenhados para páginas e JSON, não para milhões de voxels 3D, caixas de colisão AABB e busca A\* empacotada em 64 bits.
