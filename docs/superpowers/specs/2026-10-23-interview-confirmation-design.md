# Contagem confirmada de entrevistas — issue #99

Escopo definido pelo usuário: manter a contagem por candidatura; perguntar na interface se a entrevista foi realizada antes de incrementar por mudança de status; permitir alterar a contagem via API e MCP.

A contagem absoluta `interviewCount` existente passa a ser a fonte de verdade. Transições continuam registradas em `interview_events`, mas não alteram o total. PUT/POST/PATCH aceitam totais explícitos; omissão preserva o total. Patch-Application expõe o mesmo campo opcional, com validação de inteiro não negativo. Reenvios do mesmo total são idempotentes e não concedem XP adicional por contagem.

A interface pergunta ao salvar uma mudança para um status do conjunto que antes incrementava automaticamente, inclusive mudanças entre etapas desse conjunto. Sim soma um ao total do formulário; não mantém o total. Fechar o popup cancela o salvamento. Repetir o salvamento após erro deve reutilizar o total absoluto. Agendamento isolado não dispara confirmação. A pergunta esclarece que testes assíncronos não contam.

Analytics soma entrevistas realizadas, mantendo a taxa como proporção de candidaturas com ao menos uma entrevista (máximo 100%). Preservar dados legados sem migração ou inferência de notas. Não criar registros de entrevista individuais: o usuário escolheu ajuste da contagem em vez da modelagem proposta anteriormente.

Alternativas: manter incremento automático com flag de confirmação exigiria novos contratos em todos os comandos; usar contagem absoluta já existente evita incremento duplicado e preserva o contrato REST.
