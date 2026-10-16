import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { AuthProvider, useAuth } from './AuthContext';

function TestConsumer() {
  const { isAuthenticated, user } = useAuth();
  return (
    <div>
      <span data-testid="auth-status">{isAuthenticated ? 'authenticated' : 'guest'}</span>
      {user && <span data-testid="username">{user.username}</span>}
    </div>
  );
}

describe('AuthContext', () => {
  it('initializes in unauthenticated state when localStorage is empty', () => {
    render(
      <AuthProvider>
        <TestConsumer />
      </AuthProvider>
    );

    expect(screen.getByTestId('auth-status')).toHaveTextContent('guest');
  });

  it('restores authenticated state from localStorage', () => {
    localStorage.setItem('threadhub_token', 'valid-token');
    localStorage.setItem('threadhub_user', JSON.stringify({ id: 1, username: 'tester' }));

    render(
      <AuthProvider>
        <TestConsumer />
      </AuthProvider>
    );

    expect(screen.getByTestId('auth-status')).toHaveTextContent('authenticated');
    expect(screen.getByTestId('username')).toHaveTextContent('tester');
  });
});