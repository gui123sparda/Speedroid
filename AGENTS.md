# Speedroid — instruções para agentes

O Speedroid é um cliente Android para monitoramento e calibração de injeções programáveis. A referência incluída é Speeduino com assinatura `speeduino 202501`. Trate cada combinação de placa, firmware e definição INI como um alvo distinto.

## Onde trabalhar

- `app/src/main/java/com/example/tunerstudioandroid` contém a interface Kotlin, a sessão, os transportes Android, os painéis, o logger e os sensores.
- `app/src/main/java/org/clonetuner/ini` interpreta definições INI. `org/clonetuner/model` mantém parâmetros, páginas e arquivos MSQ. `org/clonetuner/comm` implementa o protocolo e a simulação.
- `app/src/main/assets/speeduino.ini` é a definição padrão. `firmware/speeduino-202501` contém a extensão GPS VSS para a placa e versão descritas naquele diretório.
- `app/src/test` cobre o núcleo. `app/src/androidTest` cobre sessões e fluxos Android. Consulte [learning.md](learning.md) para reproduzir os fluxos.

## Compatibilidade e gravação

- Para integrar outra ECU, documente a revisão do hardware, a versão e assinatura do firmware, o INI correspondente, o transporte, o framing e as capacidades testadas. Use a documentação e o código da versão alvo. Speeduino e rusEFI são referências de projetos abertos. Declare suporte apenas para as combinações validadas.
- Preserve a verificação de assinatura, CRC, separação entre telemetria e transações, readback e confirmação de burn. Mantenha a edição local separada da gravação explícita e preserve os ajustes pendentes após falhas recuperáveis.
- Preserve índices e offsets originais ao ordenar células e eixos para exibição. Use unidades e escala da definição. Represente leituras indisponíveis como ausentes.
- Valide primeiro em demonstração, com fixtures ou transporte simulado. Para um novo protocolo, use uma bancada com a placa apropriada e atuadores isolados. Registre falhas de comunicação, reconexão, leitura, escrita e persistência após reinício antes de declarar suporte.
- Conserve licenças e atribuições de firmware, definições e materiais importados. Documente a origem exata das fixtures. Use exemplos de calibração próprios para simulação.

## Verificação e entrega

Use o Gradle Wrapper e o SDK indicados no [README](README.md). Rode `./gradlew testDebugUnitTest assembleDebug` e valide o fluxo afetado no emulador. Para alterações de UI, verifique retrato, paisagem e as duas paletas. Ao publicar uma beta, valide instalação sobre a anterior, confira a assinatura, atualize o APK de distribuição e compare o hash do asset publicado com o binário validado.
