import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import en from '../i18n/en.js';
import ps from '../i18n/ps.js';
import fa from '../i18n/fa.js';
import { I18nProvider, translate, useI18n } from '../i18n/I18nContext.jsx';
import LanguageSwitcher from '../i18n/LanguageSwitcher.jsx';
import LoginPage from '../pages/LoginPage.jsx';
import { renderWithAuth } from './utils.jsx';

const placeholders = (s) => (s.match(/\{\w+\}/g) || []).sort().join(',');

describe('translations', () => {
  it.each([['Pashto', ps], ['Dari', fa]])('%s has every English key with the same placeholders', (_, dict) => {
    expect(Object.keys(dict).sort()).toEqual(Object.keys(en).sort());
    for (const key of Object.keys(en)) {
      expect(dict[key], key).toBeTruthy();
      expect(placeholders(dict[key]), key).toBe(placeholders(en[key]));
    }
  });

  it('fills placeholders and falls back to English for unknown languages', () => {
    expect(translate('en', 'common.pageOf', { page: 2, pages: 5 })).toBe('Page 2 of 5');
    expect(translate('xx', 'login.title')).toBe('Sign in');
    expect(translate('en', 'no.such.key')).toBe('no.such.key');
  });
});

function Probe() {
  const { t, dir } = useI18n();
  return <div data-testid="probe" data-dir={dir}>{t('login.title')}</div>;
}

describe('language switching', () => {
  it('switches text and page direction: English LTR, Pashto and Dari RTL', async () => {
    render(<I18nProvider initial="en"><LanguageSwitcher /><Probe /></I18nProvider>);
    expect(screen.getByTestId('probe')).toHaveTextContent('Sign in');
    expect(document.documentElement).toHaveAttribute('dir', 'ltr');
    expect(document.documentElement).toHaveAttribute('lang', 'en');

    await userEvent.click(screen.getByRole('button', { name: 'پښتو' }));
    expect(screen.getByTestId('probe')).toHaveTextContent('ننوتل');
    expect(document.documentElement).toHaveAttribute('dir', 'rtl');
    expect(document.documentElement).toHaveAttribute('lang', 'ps-AF');

    await userEvent.click(screen.getByRole('button', { name: 'دری' }));
    expect(screen.getByTestId('probe')).toHaveTextContent('ورود');
    expect(document.documentElement).toHaveAttribute('lang', 'fa-AF');
    expect(window.localStorage.getItem('netmon.language')).toBe('fa');

    await userEvent.click(screen.getByRole('button', { name: 'English' }));
    expect(document.documentElement).toHaveAttribute('dir', 'ltr');
  });

  it('renders the sign-in page in Dari with left-to-right username field', () => {
    renderWithAuth(<LoginPage />, { user: null, path: '/login', route: '/login', lang: 'fa' });
    expect(screen.getByRole('heading', { name: 'ورود' })).toBeInTheDocument();
    expect(screen.getByLabelText('نام کاربری')).toHaveAttribute('dir', 'ltr');
    expect(document.documentElement).toHaveAttribute('dir', 'rtl');
  });

  it('shows backend error codes in the selected language', async () => {
    const login = async () => {
      throw { response: { status: 401, data: { code: 'LOGIN_FAILED', message: 'Invalid username or password', requestId: 'r-1' } } };
    };
    renderWithAuth(<LoginPage />, { user: null, path: '/login', route: '/login', lang: 'ps', auth: { login } });
    await userEvent.type(screen.getByLabelText('کارن نوم'), 'ali');
    await userEvent.type(screen.getByLabelText('پټنوم'), 'x');
    await userEvent.click(screen.getByRole('button', { name: 'ننوتل' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(ps['errors.LOGIN_FAILED']);
  });
});
