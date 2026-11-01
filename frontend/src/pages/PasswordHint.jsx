import { useI18n } from '../i18n/I18nContext.jsx';

export default function PasswordHint() {
  const { t } = useI18n();
  return <div className="form-text">{t('password.hint')}</div>;
}
