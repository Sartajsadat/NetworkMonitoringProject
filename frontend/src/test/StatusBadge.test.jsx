import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import StatusBadge from '../components/StatusBadge.jsx';

describe('StatusBadge', () => {
  it('always shows a text label, not colour alone', () => {
    const { container } = render(<StatusBadge status="NO_AGENT" />);
    expect(screen.getByText('No agent')).toBeInTheDocument();
    expect(container.querySelector('i.bi')).not.toBeNull();
  });

  it('falls back to the raw value for unknown statuses', () => {
    render(<StatusBadge status="SOMETHING_NEW" />);
    expect(screen.getByText('SOMETHING_NEW')).toBeInTheDocument();
  });
});
