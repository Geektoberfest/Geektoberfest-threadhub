import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useAuth } from '../../context/AuthContext';
import { useToast } from '../../context/ToastContext';
import { Button } from '../../components/common/Button';
import { Input } from '../../components/common/Input';
import { ErrorMessage } from '../../components/common/ErrorMessage';

export function Register() {
  const [username, setUsername] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);
  const [details, setDetails] = useState([]);

  const { register, login } = useAuth();
  const toast = useToast();
  const navigate = useNavigate();

  const handleSubmit = async (e) => {
    e.preventDefault();
    setError(null);
    setDetails([]);

    if (username.length < 3) {
      const message = 'Username must be at least 3 characters';
      setError(message);
      toast.error(message);
      return;
    }
    if (password.length < 6) {
      const message = 'Password must be at least 6 characters';
      setError(message);
      toast.error(message);
      return;
    }

    setLoading(true);
    let accountCreated = false;

    try {
      await register(username, email, password);
      accountCreated = true;
      toast.success('Account created successfully.');
      // Auto login after successful registration
      await login(email, password);
      navigate('/communities');
    } catch (err) {
      // A failure of the automatic sign-in after a successful registration must not look like a
      // failed registration, and it must not suggest that the user is logged in.
      const message = accountCreated
        ? 'Account created, but signing in failed. Please log in.'
        : (err.message || 'Registration failed');
      setError(message);
      toast.error(message);
      if (err.data?.details) {
        setDetails(err.data.details);
      }
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="max-w-md mx-auto py-12">
      <div className="bg-slate-900 border border-slate-800 rounded-xl p-6 sm:p-8 shadow-xl space-y-6">
        <div className="space-y-2 text-center">
          <h1 className="text-2xl font-extrabold text-white tracking-tight">Create an Account</h1>
          <p className="text-xs text-slate-400">Join ThreadHub to start and participate in communities</p>
        </div>

        {error && <ErrorMessage message={error} details={details} />}

        <form onSubmit={handleSubmit} className="space-y-4">
          <Input
            label="Username"
            id="username"
            type="text"
            required
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            placeholder="johndoe"
            helperText="3 to 50 characters, unique"
          />

          <Input
            label="Email Address"
            id="email"
            type="email"
            required
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            placeholder="you@example.com"
          />

          <Input
            label="Password"
            id="password"
            type="password"
            required
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            placeholder="••••••••"
            helperText="At least 6 characters"
          />

          <Button
            type="submit"
            variant="primary"
            className="w-full mt-2"
            isLoading={loading}
          >
            Create Account
          </Button>
        </form>

        <div className="text-center text-xs text-slate-400 pt-2 border-t border-slate-800">
          Already have an account?{' '}
          <Link to="/login" className="text-orange-400 hover:text-orange-300 font-semibold underline">
            Log in
          </Link>
        </div>
      </div>
    </div>
  );
}
