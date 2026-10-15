import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { ProtectedRoute } from './ProtectedRoute';
import * as AuthContextModule from '../../context/AuthContext';

function renderProtectedRoute(initialEntries = ['/protected']) {
    return render(
        <MemoryRouter initialEntries={initialEntries}>
            <Routes>
                <Route path="/login" element={<div>Login Page</div>} />
                <Route element={<ProtectedRoute />}>
                    <Route path="/protected" element={<div>Protected Content</div>} />
                </Route>
            </Routes>
        </MemoryRouter>
    );
}

describe('ProtectedRoute', () => {
    it('redirects unauthenticated users to /login', () => {
        vi.spyOn(AuthContextModule, 'useAuth').mockReturnValue({
            isAuthenticated: false,
            loading: false,
        });

        renderProtectedRoute();
        expect(screen.getByText('Login Page')).toBeInTheDocument();
    });

    it('renders child routes when authenticated', () => {
        vi.spyOn(AuthContextModule, 'useAuth').mockReturnValue({
            isAuthenticated: true,
            loading: false,
        });

        renderProtectedRoute();
        expect(screen.getByText('Protected Content')).toBeInTheDocument();
    });
});