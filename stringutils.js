function isPalindrome(s) {
  const cleaned = s.toLowerCase();
  return cleaned === cleaned.split('').reverse().join('');
}

function reverseWords(s) {
  return s.split(' ').reverse().join(' ');
}

module.exports = { isPalindrome, reverseWords };
