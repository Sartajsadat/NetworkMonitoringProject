import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import StatusBadge from '../components/StatusBadge.jsx';
import { I18nProvider } from '../i18n/I18nContext.jsx';

const renderIn = (lang, ui) => render(<I18nProvider initial={lang}>{ui}</I18nProvider>);

describe('StatusBadge', () => {
  it('always shows a text label, not colour alone', () => {
    const { container } = renderIn('en', <StatusBadge status="NO_AGENT" />);
    expect(screen.getByText('No agent')).toBeInTheDocument();
    expect(container.querySelector('i.bi')).not.toBeNull();
  });

  it('translates the label', () => {
    renderIn('fa', <StatusBadge status="ONLINE" />);
    expect(screen.getByText('آنلاین')).toBeInTheDocument();
  });

  it('falls back to the raw value for unknown statuses', () => {
    renderIn('en', <StatusBadge status="SOMETHING_NEW" />);
    expect(screen.getByText('SOMETHING_NEW')).toBeInTheDocument();
  });
});
