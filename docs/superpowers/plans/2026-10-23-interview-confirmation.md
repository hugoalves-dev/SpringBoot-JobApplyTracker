# Interview Confirmation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Contar apenas entrevistas confirmadas e expor ajuste da contagem no MCP.
**Architecture:** Preservar contagem absoluta por candidatura; status gera somente histórico. React confirma incremento no salvamento. Analytics usa contagem armazenada.
**Tech Stack:** Spring Boot, Java 21, React, TypeScript, JUnit, Playwright.
**Spec:** docs/superpowers/specs/2026-10-23-interview-confirmation-design.md

## Global Constraints
Preservar dados legados, não conceder XP ao ajustar contagem, validar inteiro não negativo, manter autorização por proprietário.

## Review Focus
- Reenvio do mesmo total não incrementa novamente.
- Status junto com total explícito não soma implicitamente.
- Fechar popup não salva; não confirma mantém contagem.
- Erro de rede e nova tentativa não somam novamente.
- Múltiplas entrevistas não fazem taxa ultrapassar 100%.

### Task 1: Backend e MCP
Files: InterviewMetricsService.java, McpApplicationTools.java, McpAnalyticsTools.java e testes correspondentes.
Interfaces: PATCH /api/v1/applications/{id} com interviewCount; Patch-Application com Integer interviewCount opcional antes de archived.
- [x] Escrever testes de transição preservando contagem e analytics com 2+2+1 entrevistas em 3 candidaturas.
- [x] Executar e observar falhas; remover incremento implícito, expor campo e corrigir analytics.
- [x] Cobrir validação, omissão, reenvio e propriedade pelo REST; executar testes.

### Task 2: Confirmação na interface
Files: React src/pages/applications/ApplicationForm.tsx, src/lib/interviews.ts, tests/interview-confirmation.spec.ts.
Interfaces: requiresInterviewConfirmation(previousStatus, nextStatus) => boolean.
- [x] Testar mudança para etapa, mesma etapa, rascunho e transição entre etapas.
- [x] Adicionar diálogo no envio com Sim/Não e cancelamento, usando total absoluto.
- [x] Testar fluxo via Playwright e executar build/lint.

### Task 3: Verificação
- [x] Documentar uso REST/MCP e comportamento de confirmação.
- [x] Executar suíte backend e checks frontend; revisar diff e registrar limitações.


## Verification results
- Targeted final backend run: 66 tests passed, including ApplicationControllerIT and isolated McpAuthIT.
- Full backend run: 434 tests, 0 assertion failures, 4 setup errors in McpAuthIT due to WebAuthn challenge foreign keys left by prior classes. McpAuthIT passes in isolation; its existing cleanup does not remove passkey challenges.
- Browser suite: 86/87 passed initially; the failed retry test incorrectly selected the seeded current status. Corrected that scenario and verified all nine interview confirmation cases, including two added during review. The new-application case needed the exact required-field accessible label in its test selector.
- Frontend production build, ESLint, existing Node tests, and git diff whitespace checks passed.
- Static independent review found no blocking issues; review-requested count-only, create/replace, zero and negative cases were added.
