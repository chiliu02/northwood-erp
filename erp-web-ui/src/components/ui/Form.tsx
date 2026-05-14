import clsx from "clsx";
import type { InputHTMLAttributes, SelectHTMLAttributes, TextareaHTMLAttributes } from "react";

const INPUT_BASE =
  "block h-9 w-full rounded-md border border-border-default bg-bg-surface px-3 text-sm " +
  "text-text-primary placeholder:text-text-faint " +
  "focus:border-border-focus focus:outline-none focus:ring-1 focus:ring-border-focus " +
  "disabled:cursor-not-allowed disabled:bg-bg-subtle disabled:text-text-muted";

export function TextInput(props: InputHTMLAttributes<HTMLInputElement>) {
  return <input type="text" {...props} className={clsx(INPUT_BASE, props.className)} />;
}

export function NumberInput(props: InputHTMLAttributes<HTMLInputElement>) {
  return <input type="number" {...props} className={clsx(INPUT_BASE, "tabular-nums", props.className)} />;
}

export function DateInput(props: InputHTMLAttributes<HTMLInputElement>) {
  return <input type="date" {...props} className={clsx(INPUT_BASE, props.className)} />;
}

export function TextArea(props: TextareaHTMLAttributes<HTMLTextAreaElement>) {
  return (
    <textarea
      {...props}
      className={clsx(
        INPUT_BASE.replace("h-9", "min-h-20 py-2"),
        props.className
      )}
    />
  );
}

export function Select(props: SelectHTMLAttributes<HTMLSelectElement>) {
  return <select {...props} className={clsx(INPUT_BASE, "pr-8", props.className)} />;
}
