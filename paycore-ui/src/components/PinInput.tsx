import { useRef } from 'react';

export function PinInput({
  value,
  onChange,
  label = 'Transaction PIN',
  autoFocus = false,
  disabled = false,
}: {
  value: string;
  onChange: (pin: string) => void;
  label?: string;
  autoFocus?: boolean;
  disabled?: boolean;
}) {
  const input = useRef<HTMLInputElement>(null);

  return (
    <label className="pin-input" onClick={() => input.current?.focus()}>
      <span className="field-label">{label}</span>
      <span className="pin-boxes" aria-hidden>
        {[0, 1, 2, 3].map((i) => (
          <span key={i} className={`pin-box ${i < value.length ? 'filled' : ''} ${i === value.length ? 'active' : ''}`} />
        ))}
      </span>
      <input
        ref={input}
        className="pin-hidden"
        type="password"
        inputMode="numeric"
        autoComplete="off"
        maxLength={4}
        value={value}
        autoFocus={autoFocus}
        disabled={disabled}
        aria-label={label}
        onChange={(e) => onChange(e.target.value.replace(/\D/g, '').slice(0, 4))}
      />
    </label>
  );
}
