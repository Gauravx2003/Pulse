/**
 * Forbids hex colors anywhere in a .vue file (template, script and <style>).
 * Colors must come from design tokens (var(--...)); see design.md decision 9.
 */
const HEX = /(?<![\w&#])#(?:[0-9a-f]{8}|[0-9a-f]{6}|[0-9a-f]{3,4})(?![\w-])/gi;

export default {
  meta: {
    type: 'problem',
    docs: { description: 'disallow hex colors in Vue files; use design tokens' },
    schema: [],
    messages: {
      hex: 'Hex color "{{value}}" is not allowed in Vue files. Use a design token, e.g. var(--color-...).',
    },
  },
  create(context) {
    const sourceCode = context.sourceCode;
    return {
      Program() {
        const text = sourceCode.text;
        for (const match of text.matchAll(HEX)) {
          const start = sourceCode.getLocFromIndex(match.index);
          const end = sourceCode.getLocFromIndex(match.index + match[0].length);
          context.report({ loc: { start, end }, messageId: 'hex', data: { value: match[0] } });
        }
      },
    };
  },
};
