import { useState } from 'react';
import { useNavigate, Link } from 'react-router-dom';
import { communityApi } from '../../api/communityApi';
import { Button } from '../../components/common/Button';
import { Input } from '../../components/common/Input';
import { ErrorMessage } from '../../components/common/ErrorMessage';

const NAME_MIN_LENGTH = 3;
const NAME_MAX_LENGTH = 50;
const DESCRIPTION_MAX_LENGTH = 500;

export function CreateCommunity() {
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [touched, setTouched] = useState({ name: false });
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);
  const [details, setDetails] = useState([]);

  const navigate = useNavigate();

  const isNameValid =
    name.trim().length >= NAME_MIN_LENGTH && name.length <= NAME_MAX_LENGTH;
  const isDescriptionValid = description.length <= DESCRIPTION_MAX_LENGTH;
  const isFormValid = isNameValid && isDescriptionValid;

  const getNameError = () => {
    if (name.length > NAME_MAX_LENGTH) {
      return `Community name must not exceed ${NAME_MAX_LENGTH} characters`;
    }
    if (touched.name || name.length > 0) {
      if (!name.trim()) {
        return 'Community name is required';
      }
      if (name.trim().length < NAME_MIN_LENGTH) {
        return `Community name must be at least ${NAME_MIN_LENGTH} characters long`;
      }
    }
    return null;
  };

  const getDescriptionError = () => {
    if (description.length > DESCRIPTION_MAX_LENGTH) {
      return `Description must not exceed ${DESCRIPTION_MAX_LENGTH} characters`;
    }
    return null;
  };

  const nameError = getNameError();
  const descriptionError = getDescriptionError();

  const handleSubmit = async (e) => {
    e.preventDefault();
    setError(null);
    setDetails([]);

    if (!isFormValid) {
      setTouched({ name: true });
      return;
    }

    setLoading(true);

    try {
      const createdCommunity = await communityApi.createCommunity({
        name: name.trim(),
        description: description.trim(),
      });
      navigate(`/communities/${createdCommunity.id}`);
    } catch (err) {
      setError(err.message || 'Failed to create community');
      if (err.data?.details) {
        setDetails(err.data.details);
      }
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="max-w-xl mx-auto space-y-6 py-6">
      <Link to="/communities" className="text-xs font-semibold text-orange-400 hover:text-orange-300 transition-colors inline-flex items-center gap-1">
        &larr; Back to Communities
      </Link>

      <div className="bg-slate-900 border border-slate-800 rounded-xl p-6 sm:p-8 shadow-xl space-y-6">
        <div className="space-y-1">
          <h1 className="text-2xl font-extrabold text-white tracking-tight">Create a Community</h1>
          <p className="text-xs text-slate-400">Establish a new topic hub for discussions</p>
        </div>

        {error && <ErrorMessage message={error} details={details} />}

        <form onSubmit={handleSubmit} className="space-y-4">
          <div className="w-full space-y-1.5">
            <Input
              label="Community Name"
              id="name"
              type="text"
              required
              value={name}
              onChange={(e) => setName(e.target.value)}
              onBlur={() => setTouched((prev) => ({ ...prev, name: true }))}
              placeholder="e.g. webdev"
              error={nameError}
              helperText="3 to 50 characters, unique name"
            />
            <p className={`text-xs text-right ${name.length > NAME_MAX_LENGTH ? 'text-rose-400 font-medium' : 'text-slate-500'}`}>
              {name.length}/{NAME_MAX_LENGTH}
            </p>
          </div>

          <div className="w-full space-y-1.5">
            <label htmlFor="description" className="block text-xs font-semibold uppercase tracking-wider text-slate-400">
              Description
            </label>
            <textarea
              id="description"
              rows={4}
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              placeholder="What is this community about?"
              className={`w-full rounded-lg bg-slate-900 border ${
                descriptionError ? 'border-rose-500 focus:ring-rose-500' : 'border-slate-800 focus:border-orange-500 focus:ring-orange-500'
              } px-3.5 py-2.5 text-sm text-slate-100 placeholder-slate-500 focus:outline-none focus:ring-1 transition-colors duration-150 resize-y`}
            />
            {descriptionError && <p className="text-xs text-rose-400 font-medium">{descriptionError}</p>}
            <p className={`text-xs text-right ${description.length > DESCRIPTION_MAX_LENGTH ? 'text-rose-400 font-medium' : 'text-slate-500'}`}>
              {description.length}/{DESCRIPTION_MAX_LENGTH}
            </p>
          </div>

          <div className="flex items-center justify-end gap-3 pt-2">
            <Link to="/communities">
              <Button variant="outline" type="button">
                Cancel
              </Button>
            </Link>
            <Button variant="primary" type="submit" isLoading={loading} disabled={!isFormValid}>
              Create Community
            </Button>
          </div>
        </form>
      </div>
    </div>
  );
}
