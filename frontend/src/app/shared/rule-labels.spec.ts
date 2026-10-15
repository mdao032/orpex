import { sendProblem } from './rule-labels';

const base = { state: 'FAILED' as const, attempts: 1, issue: null, lastError: null };

describe('sendProblem', () => {
  it('announces the next automatic attempt in plain words', () => {
    const p = sendProblem({ ...base, state: 'PLANNED', issue: 'RELAY_UNREACHABLE',
      lastError: 'serveur de messagerie injoignable : Connection refused: getsockopt' }, 4, '15/10/2026 à 02:33');

    expect(p?.text).toBe('Le serveur de messagerie ne répond pas. Nouvel essai automatique le 15/10/2026 à 02:33 '
      + '(essai 2 sur 4).');
    expect(p?.level).toBe('avertissement');
    expect(p?.technical).toContain('getsockopt');
  });

  it('says what to do once the send has failed', () => {
    expect(sendProblem({ ...base, attempts: 4, issue: 'RELAY_UNREACHABLE', lastError: 'x' }, 4, null)?.text)
      .toBe('Le serveur de messagerie ne répond pas, et les 4 essais ont échoué. Prévenez l’administrateur.');
    expect(sendProblem({ ...base, issue: 'RECIPIENT_REFUSED', lastError: '550 5.1.1' }, 4, null)?.text)
      .toBe('L’adresse du destinataire a été refusée. Vérifiez-la dans votre fichier.');
    expect(sendProblem({ ...base, issue: 'MESSAGE_INCOMPLETE',
      lastError: "message incomplet : la variable {{montant}} n'a pas de valeur" }, 4, null)?.text)
      .toBe('Le message contient une variable sans valeur ({{montant}}). Corrigez le fichier.');
  });

  it('shows the decision after verification without technical detail, and nothing without a problem', () => {
    const decided = sendProblem({ ...base, state: 'SENT', issue: 'CONFIRMED_SENT', lastError: 'parti' }, 4, null);
    expect(decided).toEqual({ text: 'Déclaré parti après vérification.', level: 'info', technical: null });
    expect(sendProblem({ ...base, state: 'PLANNED', attempts: 0 }, 4, null)).toBeNull();
  });
});
