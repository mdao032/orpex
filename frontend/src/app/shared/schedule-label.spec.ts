import { daysRange, frenchDate, scheduleLabel } from './schedule-label';

const base = {
  sendDate: null, dueDate: null, daysBefore: null, startDate: null, endDate: null,
  sendTime: '09:00:00', businessDaysOnly: true,
};

describe('scheduleLabel', () => {
  it('formats ISO dates the French way', () => {
    expect(frenchDate('2027-03-01')).toBe('01/03/2027');
  });

  it('says "le" for a single day and "du … au …" for several', () => {
    expect(daysRange('2026-10-26', '2026-10-26')).toBe('le 26/10/2026');
    expect(daysRange('2027-06-07', '2027-06-11')).toBe('du 07/06/2027 au 11/06/2027');
  });

  it('describes each send mode in French', () => {
    expect(scheduleLabel({ ...base, sendMode: 'PONCTUEL', sendDate: '2027-03-10' }))
      .toBe('Le 10/03/2027 à 09:00 (jour ouvré)');
    expect(scheduleLabel({ ...base, sendMode: 'AVANT_ECHEANCE', dueDate: '2027-03-12', daysBefore: 3 }))
      .toBe("Chaque jour, de 3 jour(s) avant l'échéance jusqu'au 12/03/2027, à 09:00, jours ouvrés uniquement");
    expect(scheduleLabel({
      ...base, sendMode: 'PERIODE', startDate: '2027-03-01', endDate: '2027-03-05', businessDaysOnly: false,
    })).toBe('Chaque jour du 01/03/2027 au 05/03/2027, à 09:00, tous les jours');
  });
});
