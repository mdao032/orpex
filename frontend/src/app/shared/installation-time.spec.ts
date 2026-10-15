import { formatInZone } from './installation-time';

describe('formatInZone', () => {
  // 15 octobre 2026, 09:00 UTC : 09:00 à Bamako (UTC+0 toute l'année), 11:00 à Paris (heure d'été).
  const instant = '2026-10-15T09:00:00Z';

  it('shows the time of the installation, whatever the browser timezone', () => {
    expect(formatInZone(instant, 'dd/MM/yyyy à HH:mm', 'Africa/Bamako')).toBe('15/10/2026 à 09:00');
    expect(formatInZone(instant, 'HH:mm', 'Europe/Paris')).toBe('11:00');
  });

  it('handles the change of day and the other patterns', () => {
    expect(formatInZone('2026-10-14T23:30:00Z', 'dd/MM/yyyy HH:mm', 'Europe/Paris')).toBe('15/10/2026 01:30');
    expect(formatInZone(instant, 'dd/MM/yyyy', 'Africa/Bamako')).toBe('15/10/2026');
  });

  it('shows nothing for a missing value', () => {
    expect(formatInZone(null, 'HH:mm', 'Africa/Bamako')).toBe('');
  });
});
