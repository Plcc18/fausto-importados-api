-- Add new olfactive family values to the enum type
ALTER TYPE olfactive_family ADD VALUE IF NOT EXISTS 'aromatico';
ALTER TYPE olfactive_family ADD VALUE IF NOT EXISTS 'fougere';
ALTER TYPE olfactive_family ADD VALUE IF NOT EXISTS 'chipre';
ALTER TYPE olfactive_family ADD VALUE IF NOT EXISTS 'couro';
