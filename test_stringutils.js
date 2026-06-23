const assert = require('assert');
const { isPalindrome, reverseWords } = require('./stringutils');

function test(name, fn) {
  try {
    fn();
    console.log(`ok - ${name}`);
  } catch (err) {
    console.error(`FAIL - ${name}`);
    console.error(err);
    process.exitCode = 1;
  }
}

test('isPalindrome returns true for a palindrome', () => {
  assert.strictEqual(isPalindrome('Racecar'), true);
});

test('isPalindrome returns false for a non-palindrome', () => {
  assert.strictEqual(isPalindrome('hello'), false);
});

test('reverseWords reverses word order', () => {
  assert.strictEqual(reverseWords('hello world'), 'world hello');
});
