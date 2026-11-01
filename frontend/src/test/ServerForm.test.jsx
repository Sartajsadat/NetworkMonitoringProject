import { describe, expect, it } from 'vitest';
import { looksLikeIp } from '../pages/ServerFormPage.jsx';
import { errorMessage } from '../api/client.js';

describe('looksLikeIp', () => {
  it.each(['10.0.0.1', '255.255.255.255', '2001:db8::1', '::1'])('accepts %s', (ip) => expect(looksLikeIp(ip)).toBe(true));
  it.each(['256.1.1.1', 'example.com', '10.0.0.0/24', '1.2.3', ''])('rejects %s', (ip) => expect(looksLikeIp(ip)).toBe(false));
});

describe('errorMessage', () => {
  it('includes field errors from validation failures', () => {
    const err = { response: { status: 400, data: { message: 'Request validation failed', fieldErrors: [{ field: 'name', message: 'must not be blank' }] } } };
    expect(errorMessage(err)).toBe('Request validation failed (name: must not be blank)');
  });
  it('explains network failures', () => {
    expect(errorMessage({ message: 'Network Error' })).toMatch(/cannot reach the server/i);
  });
});
