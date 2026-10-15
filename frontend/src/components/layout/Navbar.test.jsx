import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { Navbar } from './Navbar';
import * as AuthContextModule from '../../context/AuthContext';

function renderNavbar() {
    return render(
        <MemoryRouter>
            <Navbar />
        </MemoryRouter>
    );
}

describe('Navbar', () => {
    it('renders login link when user is not authenticated', () => {
        vi.spyOn(AuthContextModule, 'useAuth').mockReturnValue({
            isAuthenticated: false,
            user: null,
            logout: vi.fn(),
        });

        renderNavbar();
        expect(screen.getByText(/log in/i)).toBeInTheDocument();
    });

    it('renders user details and logout button when authenticated', () => {
        const mockLogout = vi.fn();
        vi.spyOn(AuthContextModule, 'useAuth').mockReturnValue({
            isAuthenticated: true,
            user: { username: 'testuser' },
            logout: mockLogout,
        });

        renderNavbar();
        const logoutBtn = screen.getByRole('button', { name: /logout/i });
        expect(logoutBtn).toBeInTheDocument();

        fireEvent.click(logoutBtn);
        expect(mockLogout).toHaveBeenCalled();
    });
});