# Aprender e usar o Speedroid

## Roteiro em demonstração

1. Em **Conexão**, inicie a demonstração. Volte ao **Painel**, troque o instrumento e compare RPM, MAP, TPS e AFR. O indicador **DEMONSTRAÇÃO** identifica dados simulados.
2. Em **Mapas**, procure por `VE` ou `Ignition`. Observe os eixos e as unidades. Abra uma célula, altere-a e confira **Alterações pendentes**. Em **Ajustes** ficam os parâmetros, a calculadora de combustível requerido e as ações de leitura e gravação.
3. Em **Configurações → Projeto e arquivos**, salve uma cópia MSQ. Abra o mesmo arquivo para testar a importação. A abertura altera a cópia local e deixa a gravação na ECU pendente.
4. Em **Logs**, grave alguns segundos, pare e exporte o CSV. Compare timestamps, RPM e MAP. Experimente também o **Tooth / Composite Logger** e consulte [a documentação do logger](docs/trigger-logger/README.md).
5. Em **Configurações**, alterne os temas **Claro**, **Escuro** e **Sistema**. A opção **Manter a tela ligada** é útil ao acompanhar instrumentos. As preferências ficam no Android e são independentes dos parâmetros da ECU.

## Conceitos para ler os mapas

| Termo | O que observar |
| --- | --- |
| INI | Assinatura do firmware, endereços, unidades, descrições e organização da interface |
| MSQ | Arquivo portátil do ajuste. Salve a versão original e identifique a ECU e o firmware no nome do backup |
| RPM, MAP e TPS | Rotação, pressão absoluta no coletor e posição da borboleta. São canais e podem servir de eixos, conforme a definição |
| VE, AFR e lambda | Eficiência volumétrica, relação ar-combustível e relação normalizada ao estequiométrico. Confira as unidades da definição antes de comparar |
| Avanço | Ângulo de ignição. Leia as unidades da célula e a referência do firmware |
| Trigger e sync | Sinais de rotação e fase que permitem à ECU reconhecer a posição do motor. O logger mostra os sinais para análise |

## Primeira sessão em bancada

1. Identifique a placa e o firmware. Obtenha o INI correspondente no [manual de instalação do Speeduino](https://wiki.speeduino.com/en/Installing_Firmware). Se precisar mudar a definição no app, exporte o ajuste atual antes.
2. Use USB OTG ou Bluetooth Classic SPP conforme o hardware e siga o [guia de conexão](README.md#primeira-utilização). Faça a primeira leitura em bancada, com os atuadores isolados. Verifique assinatura, canais e unidades.
3. Salve um MSQ de referência antes de alterar parâmetros. **Ler novamente da ECU** substitui a cópia local. **Gravar na ECU** pede confirmação e transmite as páginas alteradas.
4. Compare uma leitura posterior com os valores gravados. Para a extensão de velocidade via GPS, use a [documentação da versão específica do firmware](firmware/speeduino-202501/README.md).

## Percurso pelo código

- Comece em `MainActivity.kt` e `StudioSession.kt` para acompanhar uma edição feita no app até o modelo da ECU.
- Leia `IniModel`, `Ecu` e `MsqIO` para seguir a definição de um parâmetro, seus dados em memória e a exportação.
- Compare `SimLink` e `StreamLink` e rode os testes de comunicação para entender como simulação, leitura e gravação se encaixam.
- Use [AGENTS.md](AGENTS.md) como roteiro para avaliar outro hardware ou firmware. Consulte o [firmware Speeduino](https://github.com/speeduino/speeduino) e a [documentação rusEFI](https://wiki.rusefi.com/book.pdf) ao estudar cada ecossistema.
