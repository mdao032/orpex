/** Calendrier d'une relance, sous la forme reçue de l'API (dates ISO AAAA-MM-JJ, heure HH:MM[:SS]). */
export interface Schedule {
  sendMode: 'PONCTUEL' | 'AVANT_ECHEANCE' | 'PERIODE';
  sendDate: string | null;
  dueDate: string | null;
  daysBefore: number | null;
  startDate: string | null;
  endDate: string | null;
  sendTime: string;
  businessDaysOnly: boolean;
}

/** 2027-03-01 → 01/03/2027 */
export function frenchDate(iso: string | null): string {
  if (!iso) {
    return '?';
  }
  const [year, month, day] = iso.split('-');
  return `${day}/${month}/${year}`;
}

/** Description en français : « Chaque jour du 01/03/2027 au 05/03/2027 à 09:00, jours ouvrés uniquement ». */
export function scheduleLabel(s: Schedule): string {
  const time = s.sendTime.slice(0, 5);
  const days = s.businessDaysOnly ? ', jours ouvrés uniquement' : ', tous les jours';
  switch (s.sendMode) {
    case 'PONCTUEL':
      return `Le ${frenchDate(s.sendDate)} à ${time}` + (s.businessDaysOnly ? ' (jour ouvré)' : '');
    case 'AVANT_ECHEANCE':
      return `Chaque jour, de ${s.daysBefore} jour(s) avant l'échéance jusqu'au ${frenchDate(s.dueDate)}, à ${time}${days}`;
    case 'PERIODE':
      return `Chaque jour du ${frenchDate(s.startDate)} au ${frenchDate(s.endDate)}, à ${time}${days}`;
  }
}
