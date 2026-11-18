export const queryKeys = {
  dashboards: () => ['dashboards'] as const,
  dashboard: (id: string) => ['dashboard', id] as const,
  vehicles: (filters: object) => ['vehicles', filters] as const,
  vehicle: (id: string) => ['vehicle', id] as const,
  vehicleHistory: (id: string, from: string, to: string) =>
    ['vehicle-history', id, { from, to }] as const,
  districts: (filters: object = {}) => ['districts', filters] as const,
  fleetMetrics: (from: string, to: string) => ['metrics', 'fleet', { from, to }] as const,
}
