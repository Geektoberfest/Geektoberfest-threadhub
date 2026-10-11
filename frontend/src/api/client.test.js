import { describe, it, expect, beforeEach, vi } from 'vitest';
import { apiClient } from './client';

describe('apiClient', () => {
  beforeEach(() => {
    localStorage.clear();
    vi.restoreAllMocks();
  });

  it('includes Authorization header when token exists', async () => {
    localStorage.setItem('threadhub_token', 'sample-jwt-token');

    globalThis.fetch = vi.fn().mockResolvedValue({
      ok: true,
      headers: new Headers({ 'content-type': 'application/json' }),
      json: async () => ({ success: true }),
    });

    await apiClient('/api/communities');

    expect(globalThis.fetch).toHaveBeenCalledWith(
      expect.stringContaining('/api/communities'),
      expect.objectContaining({
        headers: expect.objectContaining({
          Authorization: 'Bearer sample-jwt-token',
        }),
      })
    );
  });

  it('throws an error with message on non-ok responses', async () => {
    globalThis.fetch = vi.fn().mockResolvedValue({
      ok: false,
      status: 400,
      headers: new Headers({ 'content-type': 'application/json' }),
      json: async () => ({ message: 'Bad request payload' }),
    });

    await expect(apiClient('/api/communities')).rejects.toThrow('Bad request payload');
  });

  it('serializes JSON request body for POST methods', async () => {
    globalThis.fetch = vi.fn().mockResolvedValue({
      ok: true,
      headers: new Headers({ 'content-type': 'application/json' }),
      json: async () => ({ id: 1, name: 'Tech' }),
    });

    const payload = { name: 'Tech', description: 'Tech discussion' };
    await apiClient('/api/communities', { body: payload });

    expect(globalThis.fetch).toHaveBeenCalledWith(
      expect.stringContaining('/api/communities'),
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify(payload),
      })
    );
  });
});